/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler.main;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.rels.ClassWrapper;
import org.jetbrains.java.decompiler.main.rels.MethodWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.exps.AnnotationExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.ConstExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.NewExprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarTypeProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.modules.renamer.PoolInterceptor;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructField;
import org.jetbrains.java.decompiler.struct.StructMember;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.attr.*;
import org.jetbrains.java.decompiler.struct.consts.PrimitiveConstant;
import org.jetbrains.java.decompiler.struct.gen.FieldDescriptor;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.struct.gen.generics.*;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.util.*;
import java.util.stream.Collectors;

public class ClassWriter {

  private final ClassReference14Processor ref14processor;
  private final PoolInterceptor interceptor;

  public ClassWriter() {
    ref14processor = new ClassReference14Processor();
    interceptor = DecompilerContext.getPoolInterceptor();
  }

  private void invokeProcessors(ClassNode node) {
    ClassWrapper wrapper = node.getWrapper();
    StructClass cl = wrapper.getClassStruct();

    InitializerProcessor.extractInitializers(wrapper);
    InitializerProcessor.hideInitalizers(wrapper);

    if (node.type == ClassNode.CLASS_ROOT && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_CLASS_1_4)) {
      ref14processor.processClassReferences(node);
    }

    if (cl.hasModifier(CodeConstants.ACC_ENUM) && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM)) {
      EnumProcessor.clearEnum(wrapper);
    }

    if (DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ASSERTIONS)) {
      AssertProcessor.buildAssertions(node);
    }
  }

  public void classLambdaToJava(ClassNode node, TextBuffer buffer, Exprent method_object, int indent, BytecodeMappingTracer origTracer) {
    ClassWrapper wrapper = node.getWrapper();
    if (wrapper == null) {
      return;
    }

    boolean lambdaToAnonymous = DecompilerContext.getOption(IFernflowerPreferences.LAMBDA_TO_ANONYMOUS_CLASS);

    ClassNode outerNode = (ClassNode)DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS_NODE);
    DecompilerContext.setProperty(DecompilerContext.CURRENT_CLASS_NODE, node);

    BytecodeMappingTracer tracer = new BytecodeMappingTracer(origTracer.getCurrentSourceLine());

    try {
      StructClass cl = wrapper.getClassStruct();

      DecompilerContext.getLogger().startWriteClass(node.simpleName);

      if (node.lambdaInformation.is_method_reference) {
        if (!node.lambdaInformation.is_content_method_static && method_object != null) {
          // reference to a virtual method
          buffer.append(method_object.toJava(indent, tracer));
        }
        else {
          // reference to a static method
          buffer.append(ExprProcessor.getCastTypeName(new VarType(node.lambdaInformation.content_class_name, false)));
        }

        buffer.append("::");
        buffer.append(node.lambdaInformation.content_method_name);
      }
      else {
        // lambda method
        StructMethod mt = cl.getMethod(node.lambdaInformation.content_method_key);
        MethodWrapper methodWrapper = wrapper.getMethodWrapper(mt.getName(), mt.getDescriptor());
        MethodDescriptor md_content = MethodDescriptor.parseDescriptor(node.lambdaInformation.content_method_descriptor);
        MethodDescriptor md_lambda = MethodDescriptor.parseDescriptor(node.lambdaInformation.method_descriptor);

        if (!lambdaToAnonymous) {
          buffer.append('(');

          boolean firstParameter = true;
          int index = node.lambdaInformation.is_content_method_static ? 0 : 1;
          int start_index = md_content.params.length - md_lambda.params.length;

          for (int i = 0; i < md_content.params.length; i++) {
            if (i >= start_index) {
              if (!firstParameter) {
                buffer.append(", ");
              }

              String parameterName = methodWrapper.varproc.getVarName(new VarVersionPair(index, 0));
              buffer.append(parameterName == null ? "param" + index : parameterName); // null iff decompiled with errors

              firstParameter = false;
            }

            index += md_content.params[i].stackSize;
          }

          buffer.append(") ->");
        }

        buffer.append(" {").appendLineSeparator();
        tracer.incrementCurrentSourceLine();

        methodLambdaToJava(node, wrapper, mt, buffer, indent + 1, !lambdaToAnonymous, tracer);

        buffer.appendIndent(indent).append("}");

        addTracer(cl, mt, tracer);
      }
    }
    finally {
      DecompilerContext.setProperty(DecompilerContext.CURRENT_CLASS_NODE, outerNode);
    }

    DecompilerContext.getLogger().endWriteClass();
  }

  private static void addTracer(StructClass cls, StructMethod method, BytecodeMappingTracer tracer) {
    StructLineNumberTableAttribute lineNumberTable =
      (StructLineNumberTableAttribute)method.getAttributes().getWithKey(StructGeneralAttribute.ATTRIBUTE_LINE_NUMBER_TABLE);
    tracer.setLineNumberTable(lineNumberTable);
    DecompilerContext.getBytecodeSourceMapper().addTracer(cls.qualifiedName,
                                                          InterpreterUtil.makeUniqueKey(method.getName(), method.getDescriptor()),
                                                          tracer);
  }

  public void classToJava(ClassNode node, TextBuffer buffer, int indent, BytecodeMappingTracer tracer) {
    ClassNode outerNode = (ClassNode)DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS_NODE);
    DecompilerContext.setProperty(DecompilerContext.CURRENT_CLASS_NODE, node);

    int startLine = tracer != null ? tracer.getCurrentSourceLine() : 0;
    BytecodeMappingTracer dummy_tracer = new BytecodeMappingTracer(startLine);

    try {
      // last minute processing
      invokeProcessors(node);

      ClassWrapper wrapper = node.getWrapper();
      StructClass cl = wrapper.getClassStruct();

      DecompilerContext.getLogger().startWriteClass(cl.qualifiedName);

      // write class definition
      int start_class_def = buffer.length();
      writeClassDefinition(node, buffer, indent);

//      // count lines in class definition the easiest way
//      startLine = buffer.substring(start_class_def).toString().split(lineSeparator, -1).length - 1;

      boolean hasContent = false;

      // fields
      boolean enumFields = false;

      dummy_tracer.incrementCurrentSourceLine(buffer.countLines(start_class_def));

      for (StructField fd : cl.getFields()) {
        boolean hide = fd.isSynthetic() && DecompilerContext.getOption(IFernflowerPreferences.REMOVE_SYNTHETIC) ||
                       wrapper.getHiddenMembers().contains(InterpreterUtil.makeUniqueKey(fd.getName(), fd.getDescriptor()));
        if (hide) continue;

        boolean isEnum = fd.hasModifier(CodeConstants.ACC_ENUM) && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM);
        if (isEnum) {
          if (enumFields) {
            buffer.append(',').appendLineSeparator();
            dummy_tracer.incrementCurrentSourceLine();
          }
          enumFields = true;
        }
        else if (enumFields) {
          buffer.append(';');
          buffer.appendLineSeparator();
          buffer.appendLineSeparator();
          dummy_tracer.incrementCurrentSourceLine(2);
          enumFields = false;
        }

        fieldToJava(wrapper, cl, fd, buffer, indent + 1, dummy_tracer); // FIXME: insert real tracer

        hasContent = true;
      }

      if (enumFields) {
        buffer.append(';').appendLineSeparator();
        dummy_tracer.incrementCurrentSourceLine();
      }

      // FIXME: fields don't matter at the moment
      startLine += buffer.countLines(start_class_def);

      // methods
      for (StructMethod mt : cl.getMethods()) {
        boolean hide = mt.isSynthetic() && DecompilerContext.getOption(IFernflowerPreferences.REMOVE_SYNTHETIC) ||
                       mt.hasModifier(CodeConstants.ACC_BRIDGE) && DecompilerContext.getOption(IFernflowerPreferences.REMOVE_BRIDGE) ||
                       wrapper.getHiddenMembers().contains(InterpreterUtil.makeUniqueKey(mt.getName(), mt.getDescriptor()));
        if (hide) continue;

        int position = buffer.length();
        int storedLine = startLine;
        if (hasContent) {
          buffer.appendLineSeparator();
          startLine++;
        }
        BytecodeMappingTracer method_tracer = new BytecodeMappingTracer(startLine);
        boolean methodSkipped = !methodToJava(node, mt, buffer, indent + 1, method_tracer);
        if (!methodSkipped) {
          hasContent = true;
          addTracer(cl, mt, method_tracer);
          startLine = method_tracer.getCurrentSourceLine();
        }
        else {
          buffer.setLength(position);
          startLine = storedLine;
        }
      }

      // member classes
      for (ClassNode inner : node.nested) {
        if (inner.type == ClassNode.CLASS_MEMBER) {
          StructClass innerCl = inner.classStruct;
          boolean isSynthetic = (inner.access & CodeConstants.ACC_SYNTHETIC) != 0 || innerCl.isSynthetic() || inner.namelessConstructorStub;
          boolean hide = isSynthetic && DecompilerContext.getOption(IFernflowerPreferences.REMOVE_SYNTHETIC) ||
                         wrapper.getHiddenMembers().contains(innerCl.qualifiedName);
          if (hide) continue;

          if (hasContent) {
            buffer.appendLineSeparator();
            startLine++;
          }
          BytecodeMappingTracer class_tracer = new BytecodeMappingTracer(startLine);
          classToJava(inner, buffer, indent + 1, class_tracer);
          startLine = buffer.countLines();

          hasContent = true;
        }
      }

      buffer.appendIndent(indent).append('}');

      if (node.type != ClassNode.CLASS_ANONYMOUS) {
        buffer.appendLineSeparator();
      }
    }
    finally {
      DecompilerContext.setProperty(DecompilerContext.CURRENT_CLASS_NODE, outerNode);
    }

    DecompilerContext.getLogger().endWriteClass();
  }

  private void writeClassDefinition(ClassNode node, TextBuffer buffer, int indent) {
    if (node.type == ClassNode.CLASS_ANONYMOUS) {
      buffer.append(" {").appendLineSeparator();
      return;
    }

    ClassWrapper wrapper = node.getWrapper();
    StructClass cl = wrapper.getClassStruct();

    int flags = node.type == ClassNode.CLASS_ROOT ? cl.getAccessFlags() : node.access;
    boolean isDeprecated = cl.getAttributes().containsKey("Deprecated");
    boolean isSynthetic = (flags & CodeConstants.ACC_SYNTHETIC) != 0 || cl.getAttributes().containsKey("Synthetic");
    boolean isEnum = DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM) && (flags & CodeConstants.ACC_ENUM) != 0;
    boolean isInterface = (flags & CodeConstants.ACC_INTERFACE) != 0;
    boolean isAnnotation = (flags & CodeConstants.ACC_ANNOTATION) != 0;

    if (isDeprecated) {
      appendDeprecation(buffer, indent);
    }

    if (interceptor != null) {
      String oldName = interceptor.getOldName(cl.qualifiedName);
      appendRenameComment(buffer, oldName, MType.CLASS, indent);
    }

    if (isSynthetic) {
      appendComment(buffer, "synthetic class", indent);
    }

    appendAnnotations(buffer, cl, indent);

    buffer.appendIndent(indent);

    if (isEnum) {
      // remove abstract and final flags (JLS 8.9 Enums)
      flags &= ~CodeConstants.ACC_ABSTRACT;
      flags &= ~CodeConstants.ACC_FINAL;
    }

    appendModifiers(buffer, flags, CLASS_ALLOWED, isInterface, CLASS_EXCLUDED);

    if (isEnum) {
      buffer.append("enum ");
    }
    else if (isInterface) {
      if (isAnnotation) {
        buffer.append('@');
      }
      buffer.append("interface ");
    }
    else {
      buffer.append("class ");
    }

    GenericClassDescriptor descriptor = cl.getSignature();

    buffer.append(node.simpleName);

    if (descriptor != null && !descriptor.fparameters.isEmpty()) {
      appendTypeParameters(buffer, descriptor.fparameters, descriptor.fbounds);
    }

    buffer.append(' ');

    if (!isEnum && !isInterface && cl.superClass != null) {
      VarType supertype = new VarType(cl.superClass.getString(), true);
      if (!VarType.VARTYPE_OBJECT.equals(supertype)) {
        buffer.append("extends ");
        buffer.append(ExprProcessor.getCastTypeName(descriptor == null ? supertype : descriptor.superclass));
        buffer.append(' ');
      }
    }

    if (!isAnnotation) {
      int[] interfaces = cl.getInterfaces();
      if (interfaces.length > 0) {
        buffer.append(isInterface ? "extends " : "implements ");
        for (int i = 0; i < interfaces.length; i++) {
          if (i > 0) {
            buffer.append(", ");
          }
          VarType iface = descriptor == null ? new VarType(cl.getInterface(i), true) : descriptor.superinterfaces.get(i);
          buffer.append(ExprProcessor.getCastTypeName(iface));
        }
        buffer.append(' ');
      }
    }

    buffer.append('{').appendLineSeparator();
  }

  private void fieldToJava(ClassWrapper wrapper, StructClass cl, StructField fd, TextBuffer buffer, int indent, BytecodeMappingTracer tracer) {
    int start = buffer.length();
    boolean isInterface = cl.hasModifier(CodeConstants.ACC_INTERFACE);
    boolean isDeprecated = fd.getAttributes().containsKey("Deprecated");
    boolean isEnum = fd.hasModifier(CodeConstants.ACC_ENUM) && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM);

    if (isDeprecated) {
      appendDeprecation(buffer, indent);
    }

    if (interceptor != null) {
      String oldName = interceptor.getOldName(cl.qualifiedName + " " + fd.getName() + " " + fd.getDescriptor());
      appendRenameComment(buffer, oldName, MType.FIELD, indent);
    }

    if (fd.isSynthetic()) {
      appendComment(buffer, "synthetic field", indent);
    }

    appendAnnotations(buffer, fd, indent);

    buffer.appendIndent(indent);

    if (!isEnum) {
      appendModifiers(buffer, fd.getAccessFlags(), FIELD_ALLOWED, isInterface, FIELD_EXCLUDED);
    }

    VarType fieldType = new VarType(fd.getDescriptor(), false);

    GenericFieldDescriptor descriptor = fd.getSignature();

    if (!isEnum) {
      buffer.append(ExprProcessor.getCastTypeName(descriptor == null ? fieldType : descriptor.type));
      buffer.append(' ');
    }

    buffer.append(fd.getName());

    tracer.incrementCurrentSourceLine(buffer.countLines(start));

    Exprent initializer;
    if (fd.hasModifier(CodeConstants.ACC_STATIC)) {
      initializer = wrapper.getStaticFieldInitializers().getWithKey(InterpreterUtil.makeUniqueKey(fd.getName(), fd.getDescriptor()));
    }
    else {
      initializer = wrapper.getDynamicFieldInitializers().getWithKey(InterpreterUtil.makeUniqueKey(fd.getName(), fd.getDescriptor()));
    }
    if (initializer != null) {
      if (isEnum && initializer.type == Exprent.EXPRENT_NEW) {
        NewExprent nexpr = (NewExprent)initializer;
        nexpr.setEnumConst(true);
        buffer.append(nexpr.toJava(indent, tracer));
      }
      else {
        buffer.append(" = ");
        // FIXME: special case field initializer. Can map to more than one method (constructor) and bytecode intruction.
        initializer.getInferredExprType(descriptor == null? fieldType : descriptor.type);
        buffer.append(initializer.toJava(indent, tracer));
      }
    }
    else if (fd.hasModifier(CodeConstants.ACC_FINAL) && fd.hasModifier(CodeConstants.ACC_STATIC)) {
      StructConstantValueAttribute attr =
        (StructConstantValueAttribute)fd.getAttributes().getWithKey(StructGeneralAttribute.ATTRIBUTE_CONSTANT_VALUE);
      if (attr != null) {
        PrimitiveConstant constant = cl.getPool().getPrimitiveConstant(attr.getIndex());
        buffer.append(" = ");
        buffer.append(new ConstExprent(fieldType, constant.value, null).toJava(indent, tracer));
      }
    }

    if (!isEnum) {
      buffer.append(";").appendLineSeparator();
      tracer.incrementCurrentSourceLine();
    }
  }

  private static void methodLambdaToJava(ClassNode lambdaNode,
                                         ClassWrapper classWrapper,
                                         StructMethod mt,
                                         TextBuffer buffer,
                                         int indent,
                                         boolean codeOnly, BytecodeMappingTracer tracer) {
    MethodWrapper methodWrapper = classWrapper.getMethodWrapper(mt.getName(), mt.getDescriptor());

    MethodWrapper outerWrapper = (MethodWrapper)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD_WRAPPER);
    DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_WRAPPER, methodWrapper);

    try {
      String method_name = lambdaNode.lambdaInformation.method_name;
      MethodDescriptor md_content = MethodDescriptor.parseDescriptor(lambdaNode.lambdaInformation.content_method_descriptor);
      MethodDescriptor md_lambda = MethodDescriptor.parseDescriptor(lambdaNode.lambdaInformation.method_descriptor);

      if (!codeOnly) {
        buffer.appendIndent(indent);
        buffer.append("public ");
        buffer.append(method_name);
        buffer.append("(");

        boolean firstParameter = true;
        int index = lambdaNode.lambdaInformation.is_content_method_static ? 0 : 1;
        int start_index = md_content.params.length - md_lambda.params.length;

        for (int i = 0; i < md_content.params.length; i++) {
          if (i >= start_index) {
            if (!firstParameter) {
              buffer.append(", ");
            }

            String typeName = ExprProcessor.getCastTypeName(md_content.params[i].copy());
            if (ExprProcessor.UNDEFINED_TYPE_STRING.equals(typeName) &&
                DecompilerContext.getOption(IFernflowerPreferences.UNDEFINED_PARAM_TYPE_OBJECT)) {
              typeName = ExprProcessor.getCastTypeName(VarType.VARTYPE_OBJECT);
            }

            buffer.append(typeName);
            buffer.append(" ");

            String parameterName = methodWrapper.varproc.getVarName(new VarVersionPair(index, 0));
            buffer.append(parameterName == null ? "param" + index : parameterName); // null iff decompiled with errors

            firstParameter = false;
          }

          index += md_content.params[i].stackSize;
        }

        buffer.append(") {").appendLineSeparator();

        indent += 1;
      }

      RootStatement root = classWrapper.getMethodWrapper(mt.getName(), mt.getDescriptor()).root;
      if (!methodWrapper.decompiledWithErrors) {
        if (root != null) { // check for existence
          try {
            buffer.append(root.toJava(indent, tracer));
          }
          catch (Throwable ex) {
            DecompilerContext.getLogger().writeMessage("Method " + mt.getName() + " " + mt.getDescriptor() + " couldn't be written.", ex);
            methodWrapper.decompiledWithErrors = true;
          }
        }
      }

      if (methodWrapper.decompiledWithErrors) {
        buffer.appendIndent(indent);
        buffer.append("// $FF: Couldn't be decompiled");
        buffer.appendLineSeparator();
      }

      if (root != null) {
        tracer.addMapping(root.getDummyExit().bytecode);
      }

      if (!codeOnly) {
        indent -= 1;
        buffer.appendIndent(indent).append('}').appendLineSeparator();
      }
    }
    finally {
      DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_WRAPPER, outerWrapper);
    }
  }

  public static String toValidJavaIdentifier(String name) {
    if (name == null || name.isEmpty()) return name;

    boolean changed = false;
    StringBuilder res = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if ((i == 0 && !Character.isJavaIdentifierStart(c))
          || (i > 0 && !Character.isJavaIdentifierPart(c))) {
        changed = true;
        res.append("_");
      }
      else res.append(c);
    }
    if (!changed) {
      return name;
    }
    return res.append("/* $FF was: ").append(name).append("*/").toString();
  }

  private boolean methodToJava(ClassNode node, StructMethod mt, TextBuffer buffer, int indent, BytecodeMappingTracer tracer) {
    ClassWrapper wrapper = node.getWrapper();
    StructClass cl = wrapper.getClassStruct();
    MethodWrapper methodWrapper = wrapper.getMethodWrapper(mt.getName(), mt.getDescriptor());

    boolean hideMethod = false;
    int start_index_method = buffer.length();

    MethodWrapper outerWrapper = (MethodWrapper)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD_WRAPPER);
    DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_WRAPPER, methodWrapper);

    try {
      boolean isInterface = cl.hasModifier(CodeConstants.ACC_INTERFACE);
      boolean isAnnotation = cl.hasModifier(CodeConstants.ACC_ANNOTATION);
      boolean isEnum = cl.hasModifier(CodeConstants.ACC_ENUM) && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM);
      boolean isDeprecated = mt.getAttributes().containsKey("Deprecated");
      boolean clinit = false, init = false, dinit = false;

      MethodDescriptor md = MethodDescriptor.parseDescriptor(mt.getDescriptor());
      DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_DESCRIPTOR, md);

      int flags = mt.getAccessFlags();
      if ((flags & CodeConstants.ACC_NATIVE) != 0) {
        flags &= ~CodeConstants.ACC_STRICT; // compiler bug: a strictfp class sets all methods to strictfp
      }
      if (CodeConstants.CLINIT_NAME.equals(mt.getName())) {
        flags &= CodeConstants.ACC_STATIC; // ignore all modifiers except 'static' in a static initializer
      }

      if (isDeprecated) {
        appendDeprecation(buffer, indent);
      }

      if (interceptor != null) {
        String oldName = interceptor.getOldName(cl.qualifiedName + " " + mt.getName() + " " + mt.getDescriptor());
        appendRenameComment(buffer, oldName, MType.METHOD, indent);
      }

      boolean isSynthetic = (flags & CodeConstants.ACC_SYNTHETIC) != 0 || mt.getAttributes().containsKey("Synthetic");
      boolean isBridge = (flags & CodeConstants.ACC_BRIDGE) != 0;
      if (isSynthetic) {
        appendComment(buffer, "synthetic method", indent);
      }
      if (isBridge) {
        appendComment(buffer, "bridge method", indent);
      }

      appendAnnotations(buffer, mt, indent);

      buffer.appendIndent(indent);

      appendModifiers(buffer, flags, METHOD_ALLOWED, isInterface, METHOD_EXCLUDED);

      if (isInterface && mt.containsCode()) {
        // 'default' modifier (Java 8)
        buffer.append("default ");
      }

      String name = mt.getName();
      if (CodeConstants.INIT_NAME.equals(name)) {
        if (node.type == ClassNode.CLASS_ANONYMOUS) {
          name = "";
          dinit = true;
        }
        else {
          name = node.simpleName;
          init = true;
        }
      }
      else if (CodeConstants.CLINIT_NAME.equals(name)) {
        name = "";
        clinit = true;
      }

      GenericMethodDescriptor descriptor = mt.getSignature();
      if (descriptor != null) {
        int actualParams = md.params.length;
        List<VarVersionPair> sigFields = methodWrapper.signatureFields;
        if (sigFields != null) {
           actualParams = 0;
          for (VarVersionPair field : methodWrapper.signatureFields) {
            if (field == null) {
              actualParams++;
            }
          }
        }
        else if (isEnum && init) actualParams -= 2;
        if (actualParams != descriptor.params.size()) {
          String message = "Inconsistent generic signature in method " + mt.getName() + " " + mt.getDescriptor() + " in " + cl.qualifiedName;
          DecompilerContext.getLogger().writeMessage(message, IFernflowerLogger.Severity.WARN);
          descriptor = null;
        }
        md.addGenericDescriptor(descriptor);
      }
      boolean throwsExceptions = false;
      int paramCount = 0;

      if (!clinit && !dinit) {
        boolean thisVar = !mt.hasModifier(CodeConstants.ACC_STATIC);

        if (descriptor != null && !descriptor.fparameters.isEmpty()) {
          appendTypeParameters(buffer, descriptor.fparameters, descriptor.fbounds);
          buffer.append(' ');
        }

        if (!init) {
          buffer.append(ExprProcessor.getCastTypeName(descriptor == null ? md.ret : descriptor.ret));
          buffer.append(' ');
        }

        buffer.append(toValidJavaIdentifier(name));
        buffer.append('(');

        // parameters
        List<VarVersionPair> signFields = methodWrapper.signatureFields;

        int lastVisibleParameterIndex = -1;
        for (int i = 0; i < md.params.length; i++) {
          if (signFields == null || signFields.get(i) == null) {
            lastVisibleParameterIndex = i;
          }
        }

        boolean firstParameter = true;
        int index = isEnum && init ? 3 : thisVar ? 1 : 0;
        boolean hasDescriptor = descriptor != null;
        int start = isEnum && init && !hasDescriptor ? 2 : 0;

        if (init && hasDescriptor && !isEnum &&
            ((node.access & CodeConstants.ACC_STATIC) == 0) && node.type == ClassNode.CLASS_MEMBER) {
          index++; //Enclosing class
        }

        int params = hasDescriptor ? descriptor.params.size() : md.params.length;
        for (int i = start; i < params; i++) {
          if (hasDescriptor || (signFields == null || signFields.get(i) == null)) {
            if (!firstParameter) {
              buffer.append(", ");
            }

            appendParameterAnnotations(buffer, mt, paramCount);

            if (methodWrapper.varproc.getVarFinal(new VarVersionPair(index, 0)) == VarTypeProcessor.VAR_EXPLICIT_FINAL) {
              buffer.append("final ");
            }

            VarType parameterType = descriptor == null ? md.params[i] : descriptor.params.get(i);

            boolean isVarArg = (i == lastVisibleParameterIndex && mt.hasModifier(CodeConstants.ACC_VARARGS) && parameterType.arrayDim > 0);
            if (isVarArg) {
              parameterType = parameterType.decreaseArrayDim();
            }

            String typeName = ExprProcessor.getCastTypeName(parameterType);
            if (ExprProcessor.UNDEFINED_TYPE_STRING.equals(typeName) &&
                DecompilerContext.getOption(IFernflowerPreferences.UNDEFINED_PARAM_TYPE_OBJECT)) {
              typeName = ExprProcessor.getCastTypeName(VarType.VARTYPE_OBJECT);
            }

            buffer.append(typeName);

            if (isVarArg) {
              buffer.append("...");
            }

            buffer.append(' ');
            String parameterName = methodWrapper.varproc.getVarName(new VarVersionPair(index, 0));
            if ((flags & (CodeConstants.ACC_ABSTRACT | CodeConstants.ACC_NATIVE)) != 0) {
              String newParameterName = methodWrapper.methodStruct.getRenamer().renameAbstractParameter(parameterName, index);
              parameterName = !newParameterName.equals(parameterName) ? newParameterName : DecompilerContext.getStructContext().renameAbstractParameter(methodWrapper.methodStruct.getClassStruct().qualifiedName, mt.getName(), mt.getDescriptor(), index - (((flags & CodeConstants.ACC_STATIC) == 0) ? 1 : 0), parameterName);
            }
            buffer.append(parameterName == null ? "param" + index : parameterName); // null iff decompiled with errors

            firstParameter = false;
            paramCount++;
          }

          index += hasDescriptor ? descriptor.params.get(i).stackSize : md.params[i].stackSize;
        }

        buffer.append(')');

        StructExceptionsAttribute attr = (StructExceptionsAttribute)mt.getAttributes().getWithKey("Exceptions");
        if ((descriptor != null && !descriptor.exceptions.isEmpty()) || attr != null) {
          throwsExceptions = true;
          buffer.append(" throws ");

          for (int i = 0; i < attr.getThrowsExceptions().size(); i++) {
            if (i > 0) {
              buffer.append(", ");
            }
            VarType type = new VarType(attr.getExcClassname(i, cl.getPool()), true);
            if (descriptor != null && !descriptor.exceptions.isEmpty()) {
              type = descriptor.exceptions.get(i);
            }
            buffer.append(ExprProcessor.getCastTypeName(type));
          }
        }
      }

      tracer.incrementCurrentSourceLine(buffer.countLines(start_index_method));

      if ((flags & (CodeConstants.ACC_ABSTRACT | CodeConstants.ACC_NATIVE)) != 0) { // native or abstract method (explicit or interface)
        if (isAnnotation) {
          StructAnnDefaultAttribute attr = (StructAnnDefaultAttribute)mt.getAttributes().getWithKey("AnnotationDefault");
          if (attr != null) {
            buffer.append(" default ");
            buffer.append(attr.getDefaultValue().toJava(indent + 1, new BytecodeMappingTracer())); // dummy tracer
          }
        }

        buffer.append(';');
        buffer.appendLineSeparator();
        tracer.incrementCurrentSourceLine();
      }
      else {
        if (!clinit && !dinit) {
          buffer.append(' ');
        }

        // We do not have line information for method start, lets have it here for now
        StructLineNumberTableAttribute lineNumberTable =
          (StructLineNumberTableAttribute)mt.getAttributes().getWithKey(StructGeneralAttribute.ATTRIBUTE_LINE_NUMBER_TABLE);
        if (lineNumberTable != null && DecompilerContext.getOption(IFernflowerPreferences.USE_DEBUG_LINE_NUMBERS)) {
          buffer.setCurrentLine(lineNumberTable.getFirstLine() - 1);
        }
        buffer.append('{').appendLineSeparator();
        tracer.incrementCurrentSourceLine();

        RootStatement root = wrapper.getMethodWrapper(mt.getName(), mt.getDescriptor()).root;

        if (root != null && !methodWrapper.decompiledWithErrors) { // check for existence
          try {
            int startLine = tracer.getCurrentSourceLine();

            TextBuffer code = root.toJava(indent + 1, tracer);

            hideMethod = (clinit || dinit || hideConstructor(wrapper, init, throwsExceptions, paramCount)) && code.length() == 0;

            if (!hideMethod && lineNumberTable != null && DecompilerContext.getOption(IFernflowerPreferences.USE_DEBUG_LINE_NUMBERS)) {
              mapLines(code, lineNumberTable, tracer, startLine);
            }

            buffer.append(code);
          }
          catch (Throwable ex) {
            DecompilerContext.getLogger().writeMessage("Method " + mt.getName() + " " + mt.getDescriptor() + " couldn't be written.", ex);
            methodWrapper.decompiledWithErrors = true;
          }
        }

        if (methodWrapper.decompiledWithErrors) {
          buffer.appendIndent(indent + 1);
          buffer.append("// $FF: Couldn't be decompiled");
          buffer.appendLineSeparator();
          tracer.incrementCurrentSourceLine();
        }

        if (root != null) {
          tracer.addMapping(root.getDummyExit().bytecode);
        }
        buffer.appendIndent(indent).append('}').appendLineSeparator();
        tracer.incrementCurrentSourceLine();
      }
    }
    finally {
      DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_WRAPPER, outerWrapper);
    }

    // save total lines
    // TODO: optimize
    //tracer.setCurrentSourceLine(buffer.countLines(start_index_method));

    return !hideMethod;
  }

  private static void mapLines(TextBuffer code, StructLineNumberTableAttribute table, BytecodeMappingTracer tracer, int startLine) {
    // build line start offsets map
    HashMap<Integer, Set<Integer>> lineStartOffsets = new HashMap<Integer, Set<Integer>>();
    for (Map.Entry<Integer, Integer> entry : tracer.getMapping().entrySet()) {
      Integer lineNumber = entry.getValue() - startLine;
      Set<Integer> curr = lineStartOffsets.get(lineNumber);
      if (curr == null) {
        curr = new TreeSet<Integer>(); // requires natural sorting!
      }
      curr.add(entry.getKey());
      lineStartOffsets.put(lineNumber, curr);
    }
    String lineSeparator = DecompilerContext.getNewLineSeparator();
    StringBuilder text = code.getOriginalText();
    int pos = text.indexOf(lineSeparator);
    int lineNumber = 0;
    while (pos != -1) {
      Set<Integer> startOffsets = lineStartOffsets.get(lineNumber);
      if (startOffsets != null) {
        for (Integer offset : startOffsets) {
          int number = table.findLineNumber(offset);
          if (number >= 0) {
            code.setLineMapping(number, pos);
            break;
          }
        }
      }
      pos = text.indexOf(lineSeparator, pos+1);
      lineNumber++;
    }
  }

  private static boolean hideConstructor(ClassWrapper wrapper, boolean init, boolean throwsExceptions, int paramCount) {
    if (!init || throwsExceptions || paramCount > 0 || !DecompilerContext.getOption(IFernflowerPreferences.HIDE_DEFAULT_CONSTRUCTOR)) {
      return false;
    }

    int count = 0;
    for (StructMethod mt : wrapper.getClassStruct().getMethods()) {
      if (CodeConstants.INIT_NAME.equals(mt.getName())) {
        if (++count > 1) {
          return false;
        }
      }
    }

    return true;
  }

  private static void appendDeprecation(TextBuffer buffer, int indent) {
    buffer.appendIndent(indent).append("/** @deprecated */").appendLineSeparator();
  }

  private enum MType {CLASS, FIELD, METHOD}

  private static void appendRenameComment(TextBuffer buffer, String oldName, MType type, int indent) {
    if (oldName == null) return;

    buffer.appendIndent(indent);
    buffer.append("// $FF: renamed from: ");

    switch (type) {
      case CLASS:
        buffer.append(ExprProcessor.buildJavaClassName(oldName));
        break;

      case FIELD:
        String[] fParts = oldName.split(" ");
        FieldDescriptor fd = FieldDescriptor.parseDescriptor(fParts[2]);
        buffer.append(fParts[1]);
        buffer.append(' ');
        buffer.append(getTypePrintOut(fd.type));
        break;

      default:
        String[] mParts = oldName.split(" ");
        MethodDescriptor md = MethodDescriptor.parseDescriptor(mParts[2]);
        buffer.append(mParts[1]);
        buffer.append(" (");
        boolean first = true;
        for (VarType paramType : md.params) {
          if (!first) {
            buffer.append(", ");
          }
          first = false;
          buffer.append(getTypePrintOut(paramType));
        }
        buffer.append(") ");
        buffer.append(getTypePrintOut(md.ret));
    }

    buffer.appendLineSeparator();
  }

  private static String getTypePrintOut(VarType type) {
    String typeText = ExprProcessor.getCastTypeName(type, false);
    if (ExprProcessor.UNDEFINED_TYPE_STRING.equals(typeText) &&
        DecompilerContext.getOption(IFernflowerPreferences.UNDEFINED_PARAM_TYPE_OBJECT)) {
      typeText = ExprProcessor.getCastTypeName(VarType.VARTYPE_OBJECT, false);
    }
    return typeText;
  }

  private static void appendComment(TextBuffer buffer, String comment, int indent) {
    buffer.appendIndent(indent).append("// $FF: ").append(comment).appendLineSeparator();
  }

  private static final String[] ANNOTATION_ATTRIBUTES = {
    StructGeneralAttribute.ATTRIBUTE_RUNTIME_VISIBLE_ANNOTATIONS, StructGeneralAttribute.ATTRIBUTE_RUNTIME_INVISIBLE_ANNOTATIONS};

  private static void appendAnnotations(TextBuffer buffer, StructMember mb, int indent) {

    BytecodeMappingTracer tracer_dummy = new BytecodeMappingTracer(); // FIXME: replace with a real one

    for (String name : ANNOTATION_ATTRIBUTES) {
      StructAnnotationAttribute attribute = (StructAnnotationAttribute)mb.getAttributes().getWithKey(name);
      if (attribute != null) {
        for (AnnotationExprent annotation : attribute.getAnnotations()) {
          buffer.append(annotation.toJava(indent, tracer_dummy)).appendLineSeparator();
        }
      }
    }
  }

  private static final String[] PARAMETER_ANNOTATION_ATTRIBUTES = {
    StructGeneralAttribute.ATTRIBUTE_RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS, StructGeneralAttribute.ATTRIBUTE_RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS};

  private static void appendParameterAnnotations(TextBuffer buffer, StructMethod mt, int param) {

    BytecodeMappingTracer tracer_dummy = new BytecodeMappingTracer(); // FIXME: replace with a real one

    for (String name : PARAMETER_ANNOTATION_ATTRIBUTES) {
      StructAnnotationParameterAttribute attribute = (StructAnnotationParameterAttribute)mt.getAttributes().getWithKey(name);
      if (attribute != null) {
        List<List<AnnotationExprent>> annotations = attribute.getParamAnnotations();
        if (param < annotations.size()) {
          for (AnnotationExprent annotation : annotations.get(param)) {
            buffer.append(annotation.toJava(0, tracer_dummy)).append(' ');
          }
        }
      }
    }
  }

  private static final Map<Integer, String> MODIFIERS = new LinkedHashMap<Integer, String>() {{
    put(CodeConstants.ACC_PUBLIC, "public");
    put(CodeConstants.ACC_PROTECTED, "protected");
    put(CodeConstants.ACC_PRIVATE, "private");
    put(CodeConstants.ACC_ABSTRACT, "abstract");
    put(CodeConstants.ACC_STATIC, "static");
    put(CodeConstants.ACC_FINAL, "final");
    put(CodeConstants.ACC_STRICT, "strictfp");
    put(CodeConstants.ACC_TRANSIENT, "transient");
    put(CodeConstants.ACC_VOLATILE, "volatile");
    put(CodeConstants.ACC_SYNCHRONIZED, "synchronized");
    put(CodeConstants.ACC_NATIVE, "native");
  }};

  public static String getModifiers(int flags) {
    return MODIFIERS.entrySet().stream().filter(e -> (e.getKey() & flags) != 0).map(Map.Entry::getValue).collect(Collectors.joining(" "));
  }

  private static final int CLASS_ALLOWED =
    CodeConstants.ACC_PUBLIC | CodeConstants.ACC_PROTECTED | CodeConstants.ACC_PRIVATE | CodeConstants.ACC_ABSTRACT |
    CodeConstants.ACC_STATIC | CodeConstants.ACC_FINAL | CodeConstants.ACC_STRICT;
  private static final int FIELD_ALLOWED =
    CodeConstants.ACC_PUBLIC | CodeConstants.ACC_PROTECTED | CodeConstants.ACC_PRIVATE | CodeConstants.ACC_STATIC |
    CodeConstants.ACC_FINAL | CodeConstants.ACC_TRANSIENT | CodeConstants.ACC_VOLATILE;
  private static final int METHOD_ALLOWED =
    CodeConstants.ACC_PUBLIC | CodeConstants.ACC_PROTECTED | CodeConstants.ACC_PRIVATE | CodeConstants.ACC_ABSTRACT |
    CodeConstants.ACC_STATIC | CodeConstants.ACC_FINAL | CodeConstants.ACC_SYNCHRONIZED | CodeConstants.ACC_NATIVE | CodeConstants.ACC_STRICT;

  private static final int CLASS_EXCLUDED = CodeConstants.ACC_ABSTRACT | CodeConstants.ACC_STATIC;
  private static final int FIELD_EXCLUDED = CodeConstants.ACC_PUBLIC | CodeConstants.ACC_STATIC | CodeConstants.ACC_FINAL;
  private static final int METHOD_EXCLUDED = CodeConstants.ACC_PUBLIC | CodeConstants.ACC_ABSTRACT;

  private static void appendModifiers(TextBuffer buffer, int flags, int allowed, boolean isInterface, int excluded) {
    flags &= allowed;
    if (!isInterface) excluded = 0;
    for (int modifier : MODIFIERS.keySet()) {
      if ((flags & modifier) == modifier && (modifier & excluded) == 0) {
        buffer.append(MODIFIERS.get(modifier)).append(' ');
      }
    }
  }

  private static void appendTypeParameters(TextBuffer buffer, List<String> parameters, List<List<VarType>> bounds) {
    buffer.append('<');

    for (int i = 0; i < parameters.size(); i++) {
      if (i > 0) {
        buffer.append(", ");
      }

      buffer.append(parameters.get(i));

      List<VarType> parameterBounds = bounds.get(i);
      if (parameterBounds.size() > 1 || !"java/lang/Object".equals(parameterBounds.get(0).value)) {
        buffer.append(" extends ");
        buffer.append(ExprProcessor.getCastTypeName(parameterBounds.get(0)));
        for (int j = 1; j < parameterBounds.size(); j++) {
          buffer.append(" & ");
          buffer.append(ExprProcessor.getCastTypeName(parameterBounds.get(j)));
        }
      }
    }

    buffer.append('>');
  }
}
