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
package org.jetbrains.java.decompiler.modules.decompiler.exps;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.HashMap;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.TextBuffer;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.rels.MethodWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.CheckTypesResult;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.consts.LinkConstant;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericType;
import org.jetbrains.java.decompiler.struct.match.MatchEngine;
import org.jetbrains.java.decompiler.struct.match.MatchNode;
import org.jetbrains.java.decompiler.struct.match.MatchNode.RuleValue;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.ListStack;
import org.jetbrains.java.decompiler.util.TextUtil;

public class InvocationExprent extends Exprent {

  public static final int INVOKE_SPECIAL = 1;
  public static final int INVOKE_VIRTUAL = 2;
  public static final int INVOKE_STATIC = 3;
  public static final int INVOKE_INTERFACE = 4;
  public static final int INVOKE_DYNAMIC = 5;

  public static final int TYP_GENERAL = 1;
  public static final int TYP_INIT = 2;
  public static final int TYP_CLINIT = 3;

  private static final BitSet EMPTY_BIT_SET = new BitSet(0);

  private String name;
  private String classname;
  private boolean isStatic;
  private int functype = TYP_GENERAL;
  private Exprent instance;
  private MethodDescriptor descriptor;
  private String stringDescriptor;
  private String invokeDynamicClassSuffix;
  private int invocationTyp = INVOKE_VIRTUAL;
  private List<Exprent> lstParameters = new ArrayList<Exprent>();

  private List<VarType> genericArgs = new ArrayList<VarType>();

  public InvocationExprent() {
    super(EXPRENT_INVOCATION);
  }

  public InvocationExprent(int opcode, LinkConstant cn, ListStack<Exprent> stack, int dynamicInvocationType, BitSet bytecodeOffsets) {
    this();

    name = cn.elementname;
    classname = cn.classname;

    switch (opcode) {
      case CodeConstants.opc_invokestatic:
        invocationTyp = INVOKE_STATIC;
        break;
      case CodeConstants.opc_invokespecial:
        invocationTyp = INVOKE_SPECIAL;
        break;
      case CodeConstants.opc_invokevirtual:
        invocationTyp = INVOKE_VIRTUAL;
        break;
      case CodeConstants.opc_invokeinterface:
        invocationTyp = INVOKE_INTERFACE;
        break;
      case CodeConstants.opc_invokedynamic:
        invocationTyp = INVOKE_DYNAMIC;

        classname = "java/lang/Class"; // dummy class name
        invokeDynamicClassSuffix = "##Lambda_" + cn.index1 + "_" + cn.index2;
    }

    if (CodeConstants.INIT_NAME.equals(name)) {
      functype = TYP_INIT;
    }
    else if (CodeConstants.CLINIT_NAME.equals(name)) {
      functype = TYP_CLINIT;
    }

    stringDescriptor = cn.descriptor;
    descriptor = MethodDescriptor.parseDescriptor(cn.descriptor);

    for (VarType ignored : descriptor.params) {
      lstParameters.add(0, stack.pop());
    }

    if (opcode == CodeConstants.opc_invokedynamic) {
      if (dynamicInvocationType == CodeConstants.CONSTANT_MethodHandle_REF_invokeStatic) {
        isStatic = true;
      }
      else {
        // FIXME: remove the first parameter completely from the list. It's the object type for a virtual lambda method.
        if (!lstParameters.isEmpty()) {
          instance = lstParameters.get(0);
        }
      }
    }
    else if (opcode == CodeConstants.opc_invokestatic) {
      isStatic = true;
    }
    else {
      instance = stack.pop();
    }

    addBytecodeOffsets(bytecodeOffsets);
  }

  private InvocationExprent(InvocationExprent expr) {
    this();

    name = expr.getName();
    classname = expr.getClassname();
    isStatic = expr.isStatic();
    functype = expr.getFunctype();
    instance = expr.getInstance();
    if (instance != null) {
      instance = instance.copy();
    }
    invocationTyp = expr.getInvocationTyp();
    invokeDynamicClassSuffix = expr.getInvokeDynamicClassSuffix();
    stringDescriptor = expr.getStringDescriptor();
    descriptor = expr.getDescriptor();
    lstParameters = new ArrayList<Exprent>(expr.getLstParameters());
    ExprProcessor.copyEntries(lstParameters);

    addBytecodeOffsets(expr.bytecode);
  }

  @Override
  public VarType getExprType() {
    return descriptor.ret;
  }

  @Override
  public VarType getInferredExprType(VarType upperBound) {
    List<StructMethod> matches = getMatchedDescriptors();
    StructMethod desc = null;
    if(matches.size() == 1) desc = matches.get(0);

    VarType type = getExprType();

    genericArgs.clear();

    if(desc != null && desc.getSignature() != null) {
      VarType ret = desc.getSignature().ret;
      Map<VarType, VarType> map = new HashMap<VarType, VarType>();
      // more harm than gain
      // T -> String
      /*if(upperBound != null && desc.getSignature().fparameters.size() == 1 && desc.getSignature().fparameters.get(0).equals(ret.value)) {
        genericArgs.add(upperBound);
      }*/
      // List<T> -> List<String>
      if(upperBound != null && upperBound.isGeneric() && ret.isGeneric()) {
        List<VarType> leftArgs = ((GenericType)upperBound).getArguments();
        List<VarType> rightArgs = ((GenericType)ret).getArguments();
        List<String> fparams = desc.getSignature().fparameters;
        if(leftArgs.size() == rightArgs.size() && rightArgs.size() == fparams.size()) {
          for(int i = 0; i < leftArgs.size(); i++) {
            VarType l = leftArgs.get(i);
            VarType r = rightArgs.get(i);
            if(l != null && r.value.equals(fparams.get(i))) {
              genericArgs.add(l);
              map.put(r, l);
            }
            else {
              genericArgs.clear();
              map.clear();
              break;
            }
          }
        }
      }

      if(!map.isEmpty()) {
        // remap and return generic type
        VarType newType = ret.remap(map);
        if(ret != newType) return newType;
      }
      return ret;
    }
    return type;
  }

  @Override
  public CheckTypesResult checkExprTypeBounds() {
    CheckTypesResult result = new CheckTypesResult();

    for (int i = 0; i < lstParameters.size(); i++) {
      Exprent parameter = lstParameters.get(i);

      VarType leftType = descriptor.params[i];

      result.addMinTypeExprent(parameter, VarType.getMinTypeInFamily(leftType.typeFamily));
      result.addMaxTypeExprent(parameter, leftType);
    }

    return result;
  }

  @Override
  public List<Exprent> getAllExprents() {
    List<Exprent> lst = new ArrayList<Exprent>();
    if (instance != null) {
      lst.add(instance);
    }
    lst.addAll(lstParameters);
    return lst;
  }


  @Override
  public Exprent copy() {
    return new InvocationExprent(this);
  }

  @Override
  public TextBuffer toJava(int indent, BytecodeMappingTracer tracer) {
    TextBuffer buf = new TextBuffer();

    String super_qualifier = null;
    boolean isInstanceThis = false;

    tracer.addMapping(bytecode);

    if (isStatic) {
      if (isBoxingCall()) {
        ExprProcessor.getCastedExprent(lstParameters.get(0), descriptor.params[0], buf, indent, false, false, tracer);
        return buf;
      }
      ClassNode node = (ClassNode)DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS_NODE);
      if (node == null || !classname.equals(node.classStruct.qualifiedName)) {
        buf.append(DecompilerContext.getImportCollector().getShortName(ExprProcessor.buildJavaClassName(classname)));
      }
    }
    else {

      if (instance != null && instance.type == Exprent.EXPRENT_VAR) {
        VarExprent instvar = (VarExprent)instance;
        VarVersionPair varpaar = new VarVersionPair(instvar);

        VarProcessor vproc = instvar.getProcessor();
        if (vproc == null) {
          MethodWrapper current_meth = (MethodWrapper)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD_WRAPPER);
          if (current_meth != null) {
            vproc = current_meth.varproc;
          }
        }

        String this_classname = null;
        if (vproc != null) {
          this_classname = vproc.getThisVars().get(varpaar);
        }

        if (this_classname != null) {
          isInstanceThis = true;

          if (invocationTyp == INVOKE_SPECIAL) {
            if (!classname.equals(this_classname)) { // TODO: direct comparison to the super class?
              super_qualifier = this_classname;
            }
          }
        }
      }

      if (functype == TYP_GENERAL) {
        if (super_qualifier != null) {
          TextUtil.writeQualifiedSuper(buf, super_qualifier);
        }
        //else if (getExprType().equals(VarType.VARTYPE_OBJECT) && instance instanceof FunctionExprent && ((FunctionExprent)instance).getFuncType() == FunctionExprent.FUNCTION_CAST) {
        //  buf.append(((FunctionExprent)instance).getLstOperands().get(0).toJava(indent, tracer));
        //} // THis in theory removes casts that are not needed... ignore it for now.
        else if (instance != null) {
          TextBuffer res = instance.toJava(indent, tracer);

          VarType rightType = instance.getExprType();
          VarType leftType = new VarType(CodeConstants.TYPE_OBJECT, 0, classname);

          if (rightType.equals(VarType.VARTYPE_OBJECT) && !leftType.equals(rightType)) {
            buf.append("((").append(ExprProcessor.getCastTypeName(leftType)).append(")");

            if (instance.getPrecedence() >= FunctionExprent.getPrecedence(FunctionExprent.FUNCTION_CAST)) {
              res.enclose("(", ")");
            }
            buf.append(res).append(")");
          }
          else if (instance.getPrecedence() > getPrecedence() && !canSkipParenEnclose(instance)) {
            buf.append("(").append(res).append(")");
          }
          else {
            buf.append(res);
          }
        }
      }
    }

    switch (functype) {
      case TYP_GENERAL:
        if (VarExprent.VAR_NAMELESS_ENCLOSURE.equals(buf.toString())) {
          buf = new TextBuffer();
        }

        if (buf.length() > 0) {
          buf.append(".");

          if(genericArgs.size() != 0) {
            buf.append("<");
            for(int i = 0; i < genericArgs.size(); i++) {
              buf.append(ExprProcessor.getCastTypeName(genericArgs.get(i)));
              if(i + 1 < genericArgs.size()) {
                buf.append(", ");
              }
            }
            buf.append(">");
          }
        }
        buf.append(name);
        if (invocationTyp == INVOKE_DYNAMIC) {
          buf.append("<invokedynamic>");
        }
        buf.append("(");

        break;
      case TYP_CLINIT:
        throw new RuntimeException("Explicit invocation of " + CodeConstants.CLINIT_NAME);
      case TYP_INIT:
        if (super_qualifier != null) {
          buf.append("super(");
        }
        else if (isInstanceThis) {
          buf.append("this(");
        }
        else {
          throw new RuntimeException("Unrecognized invocation of " + CodeConstants.INIT_NAME);
        }
    }

    List<VarVersionPair> sigFields = null;
    boolean isEnum = false;
    if (functype == TYP_INIT) {
      ClassNode newNode = DecompilerContext.getClassProcessor().getMapRootClasses().get(classname);

      if (newNode != null) {  // own class
        if (newNode.getWrapper() != null) {
          MethodWrapper wrapper = newNode.getWrapper().getMethodWrapper(CodeConstants.INIT_NAME, stringDescriptor);
          if (wrapper != null || !DecompilerContext.getOption(IFernflowerPreferences.IGNORE_INVALID_BYTECODE))
            sigFields = wrapper.signatureFields;
        }
        else {
          if (newNode.type == ClassNode.CLASS_MEMBER && (newNode.access & CodeConstants.ACC_STATIC) == 0) { // non-static member class
            sigFields = new ArrayList<VarVersionPair>(Collections.nCopies(lstParameters.size(), (VarVersionPair)null));
            sigFields.set(0, new VarVersionPair(-1, 0));
          }
        }
        isEnum = newNode.classStruct.hasModifier(CodeConstants.ACC_ENUM) && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM);
      }
    }

    List<StructMethod> matches = getMatchedDescriptors();
    BitSet setAmbiguousParameters = getAmbiguousParameters(matches);
    StructMethod desc = null;
    if(matches.size() == 1) desc = matches.get(0);

    StructClass cl = DecompilerContext.getStructContext().getClass(classname);
    Map<VarType, VarType> genArgs = new HashMap<VarType, VarType>();

    // building generic info from the instance
    if(cl != null && cl.getSignature() != null && instance != null && instance.getInferredExprType(null).isGeneric()) {
      GenericType genType = (GenericType)instance.getInferredExprType(null);
      if(genType.getArguments().size() == cl.getSignature().fparameters.size()) {
        for(int i = 0; i < cl.getSignature().fparameters.size(); i++) {
          VarType from = GenericType.parse("T" + cl.getSignature().fparameters.get(i) + ";");
          VarType to = genType.getArguments().get(i);
          if(from != null && to != null) {
            genArgs.put(from, to);
          }
        }
      }
    }
    boolean firstParameter = true;
    int start = isEnum ? 2 : 0;
    for (int i = start; i < lstParameters.size(); i++) {
      if (sigFields == null || sigFields.get(i) == null) {
        if (!firstParameter) {
          buf.append(", ");
        }

        TextBuffer buff = new TextBuffer();
        boolean ambiguous = setAmbiguousParameters.get(i);
        VarType type = descriptor.params[i];
        // using info from the generic signature
        if(desc != null && desc.getSignature() != null && desc.getSignature().params.size() == lstParameters.size()) {
          type = desc.getSignature().params.get(i);
        }
        // applying generic info from the signature
        VarType remappedType = type.remap(genArgs);
        if(type != remappedType) {
          type = remappedType;
        }
        // and from the inferred generic arguments
        else if(desc != null && desc.getSignature() != null && genericArgs.size() != 0) {
          Map<VarType, VarType> genMap = new HashMap<VarType, VarType>();
          for(int j = 0; j < genericArgs.size(); j++) {
            VarType from = GenericType.parse("T" + desc.getSignature().fparameters.get(j) + ";");
            VarType to = genericArgs.get(j);
            genMap.put(from, to);
          }
          type = type.remap(genMap);
        }
        // not passing it along if what we get back is more specific
        VarType exprType = lstParameters.get(i).getInferredExprType(type);
        if(exprType != null && type != null && type.type == CodeConstants.TYPE_GENVAR) {
          type = exprType;
        }
        ExprProcessor.getCastedExprent(lstParameters.get(i), type, buff, indent, type.type != CodeConstants.TYPE_NULL, ambiguous, tracer);
        buf.append(buff);

        firstParameter = false;
      }
    }

    buf.append(")");

    return buf;
  }

  private boolean isBoxingCall() {
    if (isStatic && "valueOf".equals(name) && lstParameters.size() == 1) {
      int paramType = lstParameters.get(0).getExprType().type;

      // special handling for ambiguous types
      if (lstParameters.get(0).type == Exprent.EXPRENT_CONST) {
        // 'Integer.valueOf(1)' has '1' type detected as TYPE_BYTECHAR
        if (lstParameters.get(0).getExprType().typeFamily == CodeConstants.TYPE_FAMILY_INTEGER) {
          if (classname.equals("java/lang/Integer")) {
            // 'Integer.valueOf(40_000)' will change to '40_000' and that will be interpreted as 'char' type
            ((ConstExprent) lstParameters.get(0)).setConstType(VarType.VARTYPE_INT);
            return true;
          }
        }

        if (paramType == CodeConstants.TYPE_BYTECHAR || paramType == CodeConstants.TYPE_SHORTCHAR) {
          if (classname.equals("java/lang/Character")) {
            return true;
          }
        }
      }

      return classname.equals(getClassNameForPrimitiveType(paramType));
    }

    return false;
  }

  private static String getClassNameForPrimitiveType(int type) {
    switch (type) {
      case CodeConstants.TYPE_BOOLEAN:
        return "java/lang/Boolean";
      case CodeConstants.TYPE_BYTE:
      case CodeConstants.TYPE_BYTECHAR:
        return "java/lang/Byte";
      case CodeConstants.TYPE_CHAR:
        return "java/lang/Character";
      case CodeConstants.TYPE_SHORT:
      case CodeConstants.TYPE_SHORTCHAR:
        return "java/lang/Short";
      case CodeConstants.TYPE_INT:
        return "java/lang/Integer";
      case CodeConstants.TYPE_LONG:
        return "java/lang/Long";
      case CodeConstants.TYPE_FLOAT:
        return "java/lang/Float";
      case CodeConstants.TYPE_DOUBLE:
        return "java/lang/Double";
    }
    return null;
  }

  private boolean canSkipParenEnclose(Exprent instance) {
    if (instance.type != Exprent.EXPRENT_NEW) {
      return false;
    }

    NewExprent newExpr = (NewExprent) instance;

    if (!newExpr.isAnonymous() && !newExpr.isLambda()) {
      return this.functype == TYP_GENERAL;
    }

    return false;
  }

  private List<StructMethod> getMatchedDescriptors() {
    List<StructMethod> matches = new ArrayList<StructMethod>();

    StructClass cl = DecompilerContext.getStructContext().getClass(classname);
    if (cl == null) return matches;

    nextMethod:
    for (StructMethod mt : cl.getMethods()) {
      if (name.equals(mt.getName())) {
        MethodDescriptor md = MethodDescriptor.parseDescriptor(mt.getDescriptor());
        if (md.params.length == descriptor.params.length) {
          for (int i = 0; i < md.params.length; i++) {
            if (md.params[i].typeFamily != descriptor.params[i].typeFamily) {
              continue nextMethod;
            }
          }
          matches.add(mt);
       }
      }
    }
    return matches;
  }

  private BitSet getAmbiguousParameters(List<StructMethod> matches) {
    StructClass cl = DecompilerContext.getStructContext().getClass(classname);
    if (cl == null) return EMPTY_BIT_SET;

    if (matches.size() == 1) return EMPTY_BIT_SET;

    // check if a call is unambiguous
    StructMethod mt = cl.getMethod(InterpreterUtil.makeUniqueKey(name, stringDescriptor));
    if (mt != null) {
      MethodDescriptor md = MethodDescriptor.parseDescriptor(mt.getDescriptor());
      if (md.params.length == lstParameters.size()) {
        boolean exact = true;
        for (int i = 0; i < md.params.length; i++) {
          if (!md.params[i].equals(lstParameters.get(i).getExprType())) {
            exact = false;
            break;
          }
        }
        if (exact) return EMPTY_BIT_SET;
      }
    }

    // mark parameters
    BitSet ambiguous = new BitSet(descriptor.params.length);
    for (int i = 0; i < descriptor.params.length; i++) {
      VarType paramType = descriptor.params[i];
      for (StructMethod mtt : matches) {
        if(mtt.getSignature() != null && mtt.getSignature().params.get(i).isGeneric()) break;
        MethodDescriptor md = MethodDescriptor.parseDescriptor(mtt.getDescriptor());
        if (!paramType.equals(md.params[i])) {
          ambiguous.set(i);
          break;
        }
      }
    }
    return ambiguous;
  }

  @Override
  public void replaceExprent(Exprent oldExpr, Exprent newExpr) {
    if (oldExpr == instance) {
      instance = newExpr;
    }

    for (int i = 0; i < lstParameters.size(); i++) {
      if (oldExpr == lstParameters.get(i)) {
        lstParameters.set(i, newExpr);
      }
    }
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) return true;
    if (o == null || !(o instanceof InvocationExprent)) return false;

    InvocationExprent it = (InvocationExprent)o;
    return InterpreterUtil.equalObjects(name, it.getName()) &&
           InterpreterUtil.equalObjects(classname, it.getClassname()) &&
           isStatic == it.isStatic() &&
           InterpreterUtil.equalObjects(instance, it.getInstance()) &&
           InterpreterUtil.equalObjects(descriptor, it.getDescriptor()) &&
           functype == it.getFunctype() &&
           InterpreterUtil.equalLists(lstParameters, it.getLstParameters());
  }

  @Override
  public void getBytecodeRange(BitSet values) {
    measureBytecode(values, lstParameters);
    measureBytecode(values, instance);
    measureBytecode(values);
  }

  public List<Exprent> getLstParameters() {
    return lstParameters;
  }

  public void setLstParameters(List<Exprent> lstParameters) {
    this.lstParameters = lstParameters;
  }

  public MethodDescriptor getDescriptor() {
    return descriptor;
  }

  public void setDescriptor(MethodDescriptor descriptor) {
    this.descriptor = descriptor;
  }

  public String getClassname() {
    return classname;
  }

  public void setClassname(String classname) {
    this.classname = classname;
  }

  public int getFunctype() {
    return functype;
  }

  public void setFunctype(int functype) {
    this.functype = functype;
  }

  public Exprent getInstance() {
    return instance;
  }

  public void setInstance(Exprent instance) {
    this.instance = instance;
  }

  public boolean isStatic() {
    return isStatic;
  }

  public void setStatic(boolean isStatic) {
    this.isStatic = isStatic;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getStringDescriptor() {
    return stringDescriptor;
  }

  public void setStringDescriptor(String stringDescriptor) {
    this.stringDescriptor = stringDescriptor;
  }

  public int getInvocationTyp() {
    return invocationTyp;
  }

  public String getInvokeDynamicClassSuffix() {
    return invokeDynamicClassSuffix;
  }

  // *****************************************************************************
  // IMatchable implementation
  // *****************************************************************************

  public boolean match(MatchNode matchNode, MatchEngine engine) {

    if(!super.match(matchNode, engine)) {
      return false;
    }

    for(Entry<MatchProperties, RuleValue> rule : matchNode.getRules().entrySet()) {
      RuleValue value = rule.getValue();

      switch(rule.getKey()) {
      case EXPRENT_INVOCATION_PARAMETER:
        if(value.isVariable()) {
          if(value.parameter < lstParameters.size()) {
            if(!engine.checkAndSetVariableValue(value.value.toString(), lstParameters.get(value.parameter))) {
              return false;
            }
          } else {
            return false;
          }
        }
        break;
      case EXPRENT_INVOCATION_CLASS:
        if(!value.value.equals(this.classname)) {
          return false;
        }
        break;
      case EXPRENT_INVOCATION_SIGNATURE:
        if(!value.value.equals(this.name + this.stringDescriptor)) {
          return false;
        }
        break;
      }

    }

    return true;
  }

}
