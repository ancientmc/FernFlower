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
package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.code.Instruction;
import org.jetbrains.java.decompiler.code.InstructionSequence;
import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.TextBuffer;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.DirectGraph;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.DirectNode;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.FlattenStatementsHelper;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.FlattenStatementsHelper.FinallyPathWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.attr.StructBootstrapMethodsAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructGeneralAttribute;
import org.jetbrains.java.decompiler.struct.consts.ConstantPool;
import org.jetbrains.java.decompiler.struct.consts.LinkConstant;
import org.jetbrains.java.decompiler.struct.consts.PooledConstant;
import org.jetbrains.java.decompiler.struct.consts.PrimitiveConstant;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericMain;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericType;

import java.util.*;

public class ExprProcessor implements CodeConstants {

  public static final String UNDEFINED_TYPE_STRING = "<undefinedtype>";
  public static final String UNKNOWN_TYPE_STRING = "<unknown>";
  public static final String NULL_TYPE_STRING = "<null>";

  private static final HashMap<Integer, Integer> mapConsts = new HashMap<Integer, Integer>();

  static {

    // mapConsts.put(new Integer(opc_i2l), new
    // Integer(FunctionExprent.FUNCTION_I2L));
    // mapConsts.put(new Integer(opc_i2f), new
    // Integer(FunctionExprent.FUNCTION_I2F));
    // mapConsts.put(new Integer(opc_i2d), new
    // Integer(FunctionExprent.FUNCTION_I2D));
    // mapConsts.put(new Integer(opc_l2i), new
    // Integer(FunctionExprent.FUNCTION_L2I));
    // mapConsts.put(new Integer(opc_l2f), new
    // Integer(FunctionExprent.FUNCTION_L2F));
    // mapConsts.put(new Integer(opc_l2d), new
    // Integer(FunctionExprent.FUNCTION_L2D));
    // mapConsts.put(new Integer(opc_f2i), new
    // Integer(FunctionExprent.FUNCTION_F2I));
    // mapConsts.put(new Integer(opc_f2l), new
    // Integer(FunctionExprent.FUNCTION_F2L));
    // mapConsts.put(new Integer(opc_f2d), new
    // Integer(FunctionExprent.FUNCTION_F2D));
    // mapConsts.put(new Integer(opc_d2i), new
    // Integer(FunctionExprent.FUNCTION_D2I));
    // mapConsts.put(new Integer(opc_d2l), new
    // Integer(FunctionExprent.FUNCTION_D2L));
    // mapConsts.put(new Integer(opc_d2f), new
    // Integer(FunctionExprent.FUNCTION_D2F));
    // mapConsts.put(new Integer(opc_i2b), new
    // Integer(FunctionExprent.FUNCTION_I2B));
    // mapConsts.put(new Integer(opc_i2c), new
    // Integer(FunctionExprent.FUNCTION_I2C));
    // mapConsts.put(new Integer(opc_i2s), new
    // Integer(FunctionExprent.FUNCTION_I2S));

    mapConsts.put(new Integer(opc_arraylength), new Integer(FunctionExprent.FUNCTION_ARRAY_LENGTH));
    mapConsts.put(new Integer(opc_checkcast), new Integer(FunctionExprent.FUNCTION_CAST));
    mapConsts.put(new Integer(opc_instanceof), new Integer(FunctionExprent.FUNCTION_INSTANCEOF));
  }

  private static final VarType[] consts =
    new VarType[]{VarType.VARTYPE_INT, VarType.VARTYPE_FLOAT, VarType.VARTYPE_LONG, VarType.VARTYPE_DOUBLE, VarType.VARTYPE_CLASS,
      VarType.VARTYPE_STRING};

  private static final VarType[] vartypes =
    new VarType[]{VarType.VARTYPE_INT, VarType.VARTYPE_LONG, VarType.VARTYPE_FLOAT, VarType.VARTYPE_DOUBLE, VarType.VARTYPE_OBJECT};

  private static final VarType[] arrtypes =
    new VarType[]{VarType.VARTYPE_INT, VarType.VARTYPE_LONG, VarType.VARTYPE_FLOAT, VarType.VARTYPE_DOUBLE, VarType.VARTYPE_OBJECT,
      VarType.VARTYPE_BOOLEAN, VarType.VARTYPE_CHAR, VarType.VARTYPE_SHORT};

  private static final int[] func1 =
    new int[]{FunctionExprent.FUNCTION_ADD, FunctionExprent.FUNCTION_SUB, FunctionExprent.FUNCTION_MUL, FunctionExprent.FUNCTION_DIV,
      FunctionExprent.FUNCTION_REM};

  private static final int[] func2 =
    new int[]{FunctionExprent.FUNCTION_SHL, FunctionExprent.FUNCTION_SHR, FunctionExprent.FUNCTION_USHR, FunctionExprent.FUNCTION_AND,
      FunctionExprent.FUNCTION_OR, FunctionExprent.FUNCTION_XOR};

  private static final int[] func3 =
    new int[]{FunctionExprent.FUNCTION_I2L, FunctionExprent.FUNCTION_I2F, FunctionExprent.FUNCTION_I2D, FunctionExprent.FUNCTION_L2I,
      FunctionExprent.FUNCTION_L2F, FunctionExprent.FUNCTION_L2D, FunctionExprent.FUNCTION_F2I, FunctionExprent.FUNCTION_F2L,
      FunctionExprent.FUNCTION_F2D,
      FunctionExprent.FUNCTION_D2I, FunctionExprent.FUNCTION_D2L, FunctionExprent.FUNCTION_D2F, FunctionExprent.FUNCTION_I2B,
      FunctionExprent.FUNCTION_I2C,
      FunctionExprent.FUNCTION_I2S};

  private static final int[] func4 =
    new int[]{FunctionExprent.FUNCTION_LCMP, FunctionExprent.FUNCTION_FCMPL, FunctionExprent.FUNCTION_FCMPG, FunctionExprent.FUNCTION_DCMPL,
      FunctionExprent.FUNCTION_DCMPG};

  private static final int[] func5 =
    new int[]{IfExprent.IF_EQ, IfExprent.IF_NE, IfExprent.IF_LT, IfExprent.IF_GE, IfExprent.IF_GT, IfExprent.IF_LE};

  private static final int[] func6 =
    new int[]{IfExprent.IF_ICMPEQ, IfExprent.IF_ICMPNE, IfExprent.IF_ICMPLT, IfExprent.IF_ICMPGE, IfExprent.IF_ICMPGT, IfExprent.IF_ICMPLE,
      IfExprent.IF_ACMPEQ, IfExprent.IF_ACMPNE};

  private static final int[] func7 = new int[]{IfExprent.IF_NULL, IfExprent.IF_NONNULL};

  private static final int[] func8 = new int[]{MonitorExprent.MONITOR_ENTER, MonitorExprent.MONITOR_EXIT};

  private static final int[] arr_type =
    new int[]{CodeConstants.TYPE_BOOLEAN, CodeConstants.TYPE_CHAR, CodeConstants.TYPE_FLOAT, CodeConstants.TYPE_DOUBLE,
      CodeConstants.TYPE_BYTE, CodeConstants.TYPE_SHORT, CodeConstants.TYPE_INT, CodeConstants.TYPE_LONG};

  private static final int[] negifs =
    new int[]{IfExprent.IF_NE, IfExprent.IF_EQ, IfExprent.IF_GE, IfExprent.IF_LT, IfExprent.IF_LE, IfExprent.IF_GT, IfExprent.IF_NONNULL,
      IfExprent.IF_NULL, IfExprent.IF_ICMPNE, IfExprent.IF_ICMPEQ, IfExprent.IF_ICMPGE, IfExprent.IF_ICMPLT, IfExprent.IF_ICMPLE,
      IfExprent.IF_ICMPGT, IfExprent.IF_ACMPNE,
      IfExprent.IF_ACMPEQ};

  private static final String[] typeNames = new String[]{"byte", "char", "double", "float", "int", "long", "short", "boolean",};

  private final VarProcessor varProcessor = (VarProcessor)DecompilerContext.getProperty(DecompilerContext.CURRENT_VAR_PROCESSOR);

  public void processStatement(RootStatement root, StructClass cl) {

    FlattenStatementsHelper flatthelper = new FlattenStatementsHelper();
    DirectGraph dgraph = flatthelper.buildDirectGraph(root);

    //		try {
    //			DotExporter.toDotFile(dgraph, new File("c:\\Temp\\gr12_my.dot"));
    //		} catch (Exception ex) {
    //			ex.printStackTrace();
    //		}

    // collect finally entry points
    Set<String> setFinallyShortRangeEntryPoints = new HashSet<String>();
    for (List<FinallyPathWrapper> lst : dgraph.mapShortRangeFinallyPaths.values()) {
      for (FinallyPathWrapper finwrap : lst) {
        setFinallyShortRangeEntryPoints.add(finwrap.entry);
      }
    }

    Set<String> setFinallyLongRangeEntryPaths = new HashSet<String>();
    for (List<FinallyPathWrapper> lst : dgraph.mapLongRangeFinallyPaths.values()) {
      for (FinallyPathWrapper finwrap : lst) {
        setFinallyLongRangeEntryPaths.add(finwrap.source + "##" + finwrap.entry);
      }
    }

    Map<String, VarExprent> mapCatch = new HashMap<String, VarExprent>();
    collectCatchVars(root, flatthelper, mapCatch);

    Map<DirectNode, Map<String, PrimitiveExprsList>> mapData = new HashMap<DirectNode, Map<String, PrimitiveExprsList>>();

    LinkedList<DirectNode> stack = new LinkedList<DirectNode>();
    LinkedList<LinkedList<String>> stackEntryPoint = new LinkedList<LinkedList<String>>();

    stack.add(dgraph.first);
    stackEntryPoint.add(new LinkedList<String>());

    Map<String, PrimitiveExprsList> map = new HashMap<String, PrimitiveExprsList>();
    map.put(null, new PrimitiveExprsList());
    mapData.put(dgraph.first, map);

    while (!stack.isEmpty()) {

      DirectNode node = stack.removeFirst();
      LinkedList<String> entrypoints = stackEntryPoint.removeFirst();

      PrimitiveExprsList data;
      if (mapCatch.containsKey(node.id)) {
        data = getExpressionData(mapCatch.get(node.id));
      }
      else {
        data = mapData.get(node).get(buildEntryPointKey(entrypoints));
      }

      BasicBlockStatement block = node.block;
      if (block != null) {
        processBlock(block, data, cl);
        block.setExprents(data.getLstExprents());
      }

      String currentEntrypoint = entrypoints.isEmpty() ? null : entrypoints.getLast();

      for (DirectNode nd : node.succs) {

        boolean isSuccessor = true;
        if (currentEntrypoint != null && dgraph.mapLongRangeFinallyPaths.containsKey(node.id)) {
          isSuccessor = false;
          for (FinallyPathWrapper finwraplong : dgraph.mapLongRangeFinallyPaths.get(node.id)) {
            if (finwraplong.source.equals(currentEntrypoint) && finwraplong.destination.equals(nd.id)) {
              isSuccessor = true;
              break;
            }
          }
        }

        if (isSuccessor) {

          Map<String, PrimitiveExprsList> mapSucc = mapData.get(nd);
          if (mapSucc == null) {
            mapData.put(nd, mapSucc = new HashMap<String, PrimitiveExprsList>());
          }

          LinkedList<String> ndentrypoints = new LinkedList<String>(entrypoints);

          if (setFinallyLongRangeEntryPaths.contains(node.id + "##" + nd.id)) {
            ndentrypoints.addLast(node.id);
          }
          else if (!setFinallyShortRangeEntryPoints.contains(nd.id) && dgraph.mapLongRangeFinallyPaths.containsKey(node.id)) {
            ndentrypoints.removeLast(); // currentEntrypoint should
            // not be null at this point
          }

          // handling of entry point loops
          int succ_entry_index = ndentrypoints.indexOf(nd.id);
          if (succ_entry_index >=
              0) { // we are in a loop (e.g. continue in a finally block), drop all entry points in the list beginning with succ_entry_index
            for (int elements_to_remove = ndentrypoints.size() - succ_entry_index; elements_to_remove > 0; elements_to_remove--) {
              ndentrypoints.removeLast();
            }
          }

          String ndentrykey = buildEntryPointKey(ndentrypoints);
          if (!mapSucc.containsKey(ndentrykey)) {

            mapSucc.put(ndentrykey, copyVarExprents(data.copyStack()));

            stack.add(nd);
            stackEntryPoint.add(ndentrypoints);
          }
        }
      }
    }

    initStatementExprents(root);
  }

  // FIXME: Ugly code, to be rewritten. A tuple class is needed.
  private static String buildEntryPointKey(LinkedList<String> entrypoints) {
    if (entrypoints.isEmpty()) {
      return null;
    }
    else {
      StringBuilder buffer = new StringBuilder();
      for (String point : entrypoints) {
        buffer.append(point);
        buffer.append(":");
      }
      return buffer.toString();
    }
  }

  private static PrimitiveExprsList copyVarExprents(PrimitiveExprsList data) {
    ExprentStack stack = data.getStack();
    copyEntries(stack);
    return data;
  }

  public static void copyEntries(List<Exprent> stack) {
    for (int i = 0; i < stack.size(); i++) {
      stack.set(i, stack.get(i).copy());
    }
  }

  private static void collectCatchVars(Statement stat, FlattenStatementsHelper flatthelper, Map<String, VarExprent> map) {

    List<VarExprent> lst = null;

    if (stat.type == Statement.TYPE_CATCHALL) {
      CatchAllStatement catchall = (CatchAllStatement)stat;
      if (!catchall.isFinally()) {
        lst = catchall.getVars();
      }
    }
    else if (stat.type == Statement.TYPE_TRYCATCH) {
      lst = ((CatchStatement)stat).getVars();
    }

    if (lst != null) {
      for (int i = 1; i < stat.getStats().size(); i++) {
        map.put(flatthelper.getMapDestinationNodes().get(stat.getStats().get(i).id)[0], lst.get(i - 1));
      }
    }

    for (Statement st : stat.getStats()) {
      collectCatchVars(st, flatthelper, map);
    }
  }

  private static void initStatementExprents(Statement stat) {
    stat.initExprents();

    for (Statement st : stat.getStats()) {
      initStatementExprents(st);
    }
  }

  public void processBlock(BasicBlockStatement stat, PrimitiveExprsList data, StructClass cl) {

    ConstantPool pool = cl.getPool();
    StructBootstrapMethodsAttribute bootstrap =
      (StructBootstrapMethodsAttribute)cl.getAttributes().getWithKey(StructGeneralAttribute.ATTRIBUTE_BOOTSTRAP_METHODS);

    BasicBlock block = stat.getBlock();

    ExprentStack stack = data.getStack();
    List<Exprent> exprlist = data.getLstExprents();

    InstructionSequence seq = block.getSeq();

    for (int i = 0; i < seq.length(); i++) {

      Instruction instr = seq.getInstr(i);
      Integer bytecode_offset = block.getOldOffset(i);
      BitSet bytecode_offsets = null;
      if (bytecode_offset >= 0) {
        bytecode_offsets = new BitSet();
        bytecode_offsets.set(bytecode_offset);
      }

      switch (instr.opcode) {
        case opc_aconst_null:
          pushEx(stack, exprlist, new ConstExprent(VarType.VARTYPE_NULL, null, bytecode_offsets));
          break;
        case opc_bipush:
        case opc_sipush:
          pushEx(stack, exprlist, new ConstExprent(instr.getOperand(0), true, bytecode_offsets));
          break;
        case opc_lconst_0:
        case opc_lconst_1:
          pushEx(stack, exprlist, new ConstExprent(VarType.VARTYPE_LONG, new Long(instr.opcode - opc_lconst_0), bytecode_offsets));
          break;
        case opc_fconst_0:
        case opc_fconst_1:
        case opc_fconst_2:
          pushEx(stack, exprlist, new ConstExprent(VarType.VARTYPE_FLOAT, new Float(instr.opcode - opc_fconst_0), bytecode_offsets));
          break;
        case opc_dconst_0:
        case opc_dconst_1:
          pushEx(stack, exprlist, new ConstExprent(VarType.VARTYPE_DOUBLE, new Double(instr.opcode - opc_dconst_0), bytecode_offsets));
          break;
        case opc_ldc:
        case opc_ldc_w:
        case opc_ldc2_w:
          PooledConstant cn = pool.getConstant(instr.getOperand(0));
          if (cn instanceof PrimitiveConstant) {
            pushEx(stack, exprlist, new ConstExprent(consts[cn.type - CONSTANT_Integer], ((PrimitiveConstant)cn).value, bytecode_offsets));
          }
          else if (cn instanceof LinkConstant) {
            //TODO: for now treat Links as Strings
            pushEx(stack, exprlist, new ConstExprent(VarType.VARTYPE_STRING, ((LinkConstant)cn).elementname , bytecode_offsets));
          }
          break;
        case opc_iload:
        case opc_lload:
        case opc_fload:
        case opc_dload:
        case opc_aload:
          VarExprent varExprent = new VarExprent(instr.getOperand(0), vartypes[instr.opcode - opc_iload], varProcessor);
          varProcessor.findLVT(varExprent, bytecode_offset+instr.length());
          pushEx(stack, exprlist, varExprent);
          break;
        case opc_iaload:
        case opc_laload:
        case opc_faload:
        case opc_daload:
        case opc_aaload:
        case opc_baload:
        case opc_caload:
        case opc_saload:
          Exprent index = stack.pop();
          Exprent arr = stack.pop();

          VarType vartype = null;
          switch (instr.opcode) {
            case opc_laload:
              vartype = VarType.VARTYPE_LONG;
              break;
            case opc_daload:
              vartype = VarType.VARTYPE_DOUBLE;
          }
          pushEx(stack, exprlist, new ArrayExprent(arr, index, arrtypes[instr.opcode - opc_iaload], bytecode_offsets), vartype);
          break;
        case opc_istore:
        case opc_lstore:
        case opc_fstore:
        case opc_dstore:
        case opc_astore:
          Exprent top = stack.pop();
          int varindex = instr.getOperand(0);
          varExprent = new VarExprent(varindex, vartypes[instr.opcode - opc_istore], varProcessor);
          varProcessor.findLVT(varExprent, bytecode_offset+instr.length());
          AssignmentExprent assign = new AssignmentExprent(varExprent, top, bytecode_offsets);
          exprlist.add(assign);
          break;
        case opc_iastore:
        case opc_lastore:
        case opc_fastore:
        case opc_dastore:
        case opc_aastore:
        case opc_bastore:
        case opc_castore:
        case opc_sastore:
          Exprent value = stack.pop();
          Exprent index_store = stack.pop();
          Exprent arr_store = stack.pop();
          AssignmentExprent arrassign =
            new AssignmentExprent(new ArrayExprent(arr_store, index_store, arrtypes[instr.opcode - opc_iastore], bytecode_offsets), value, bytecode_offsets);
          exprlist.add(arrassign);
          break;
        case opc_iadd:
        case opc_ladd:
        case opc_fadd:
        case opc_dadd:
        case opc_isub:
        case opc_lsub:
        case opc_fsub:
        case opc_dsub:
        case opc_imul:
        case opc_lmul:
        case opc_fmul:
        case opc_dmul:
        case opc_idiv:
        case opc_ldiv:
        case opc_fdiv:
        case opc_ddiv:
        case opc_irem:
        case opc_lrem:
        case opc_frem:
        case opc_drem:
          pushEx(stack, exprlist, new FunctionExprent(func1[(instr.opcode - opc_iadd) / 4], stack, bytecode_offsets));
          break;
        case opc_ishl:
        case opc_lshl:
        case opc_ishr:
        case opc_lshr:
        case opc_iushr:
        case opc_lushr:
        case opc_iand:
        case opc_land:
        case opc_ior:
        case opc_lor:
        case opc_ixor:
        case opc_lxor:
          pushEx(stack, exprlist, new FunctionExprent(func2[(instr.opcode - opc_ishl) / 2], stack, bytecode_offsets));
          break;
        case opc_ineg:
        case opc_lneg:
        case opc_fneg:
        case opc_dneg:
          pushEx(stack, exprlist, new FunctionExprent(FunctionExprent.FUNCTION_NEG, stack, bytecode_offsets));
          break;
        case opc_iinc:
          VarExprent vevar = new VarExprent(instr.getOperand(0), VarType.VARTYPE_INT, varProcessor);
          varProcessor.findLVT(vevar,bytecode_offset+instr.length());
          exprlist.add(new AssignmentExprent(vevar, new FunctionExprent(
            instr.getOperand(1) < 0 ? FunctionExprent.FUNCTION_SUB : FunctionExprent.FUNCTION_ADD, Arrays
            .asList(vevar.copy(), new ConstExprent(VarType.VARTYPE_INT, Math.abs(instr.getOperand(1)), null)),
            bytecode_offsets), bytecode_offsets));
          break;
        case opc_i2l:
        case opc_i2f:
        case opc_i2d:
        case opc_l2i:
        case opc_l2f:
        case opc_l2d:
        case opc_f2i:
        case opc_f2l:
        case opc_f2d:
        case opc_d2i:
        case opc_d2l:
        case opc_d2f:
        case opc_i2b:
        case opc_i2c:
        case opc_i2s:
          pushEx(stack, exprlist, new FunctionExprent(func3[instr.opcode - opc_i2l], stack, bytecode_offsets));
          break;
        case opc_lcmp:
        case opc_fcmpl:
        case opc_fcmpg:
        case opc_dcmpl:
        case opc_dcmpg:
          pushEx(stack, exprlist, new FunctionExprent(func4[instr.opcode - opc_lcmp], stack, bytecode_offsets));
          break;
        case opc_ifeq:
        case opc_ifne:
        case opc_iflt:
        case opc_ifge:
        case opc_ifgt:
        case opc_ifle:
          exprlist.add(new IfExprent(negifs[func5[instr.opcode - opc_ifeq]], stack, bytecode_offsets));
          break;
        case opc_if_icmpeq:
        case opc_if_icmpne:
        case opc_if_icmplt:
        case opc_if_icmpge:
        case opc_if_icmpgt:
        case opc_if_icmple:
        case opc_if_acmpeq:
        case opc_if_acmpne:
          exprlist.add(new IfExprent(negifs[func6[instr.opcode - opc_if_icmpeq]], stack, bytecode_offsets));
          break;
        case opc_ifnull:
        case opc_ifnonnull:
          exprlist.add(new IfExprent(negifs[func7[instr.opcode - opc_ifnull]], stack, bytecode_offsets));
          break;
        case opc_tableswitch:
        case opc_lookupswitch:
          exprlist.add(new SwitchExprent(stack.pop(), bytecode_offsets));
        break;
        case opc_ireturn:
        case opc_lreturn:
        case opc_freturn:
        case opc_dreturn:
        case opc_areturn:
        case opc_return:
        case opc_athrow:
          exprlist.add(new ExitExprent(instr.opcode == opc_athrow ? ExitExprent.EXIT_THROW : ExitExprent.EXIT_RETURN,
                                       instr.opcode == opc_return ? null : stack.pop(),
                                       instr.opcode == opc_athrow
                                       ? null
                                       : ((MethodDescriptor)DecompilerContext
                                         .getProperty(DecompilerContext.CURRENT_METHOD_DESCRIPTOR)).ret,
                                       bytecode_offsets));
        break;
        case opc_monitorenter:
        case opc_monitorexit:
          exprlist.add(new MonitorExprent(func8[instr.opcode - opc_monitorenter], stack.pop(), bytecode_offsets));
          break;
        case opc_checkcast:
        case opc_instanceof:
          stack.push(new ConstExprent(new VarType(pool.getPrimitiveConstant(instr.getOperand(0)).getString(), true), null, null));
        case opc_arraylength:
          pushEx(stack, exprlist, new FunctionExprent(mapConsts.get(instr.opcode).intValue(), stack, bytecode_offsets));
          break;
        case opc_getstatic:
        case opc_getfield:
          pushEx(stack, exprlist,
                 new FieldExprent(pool.getLinkConstant(instr.getOperand(0)), instr.opcode == opc_getstatic ? null : stack.pop(), bytecode_offsets));
          break;
        case opc_putstatic:
        case opc_putfield:
          Exprent valfield = stack.pop();
          Exprent exprfield =
            new FieldExprent(pool.getLinkConstant(instr.getOperand(0)), instr.opcode == opc_putstatic ? null : stack.pop(), bytecode_offsets);
          exprlist.add(new AssignmentExprent(exprfield, valfield, bytecode_offsets));
          break;
        case opc_invokevirtual:
        case opc_invokespecial:
        case opc_invokestatic:
        case opc_invokeinterface:
        case opc_invokedynamic:
          if (instr.opcode != opc_invokedynamic || instr.bytecode_version >= CodeConstants.BYTECODE_JAVA_7) {

            LinkConstant invoke_constant = pool.getLinkConstant(instr.getOperand(0));
            int dynamic_invokation_type = -1;

            if (instr.opcode == opc_invokedynamic && bootstrap != null) {
              List<PooledConstant> bootstrap_arguments = bootstrap.getMethodArguments(invoke_constant.index1);
              LinkConstant content_method_handle = (LinkConstant)bootstrap_arguments.get(1);

              dynamic_invokation_type = content_method_handle.index1;
            }

            InvocationExprent exprinv = new InvocationExprent(instr.opcode, invoke_constant, stack, dynamic_invokation_type, bytecode_offsets);
            if (exprinv.getDescriptor().ret.type == CodeConstants.TYPE_VOID) {
              exprlist.add(exprinv);
            }
            else {
              pushEx(stack, exprlist, exprinv);
            }
          }
          break;
        case opc_new:
        case opc_anewarray:
        case opc_multianewarray:
          int dimensions = (instr.opcode == opc_new) ? 0 : (instr.opcode == opc_anewarray) ? 1 : instr.getOperand(1);
          VarType arrType = new VarType(pool.getPrimitiveConstant(instr.getOperand(0)).getString(), true);
          if (instr.opcode != opc_multianewarray) {
            arrType = arrType.resizeArrayDim(arrType.arrayDim + dimensions);
          }
          pushEx(stack, exprlist, new NewExprent(arrType, stack, dimensions, bytecode_offsets));
          break;
        case opc_newarray:
          pushEx(stack, exprlist, new NewExprent(new VarType(arr_type[instr.getOperand(0) - 4], 1), stack, 1, bytecode_offsets));
          break;
        case opc_dup:
          pushEx(stack, exprlist, stack.getByOffset(-1).copy());
          break;
        case opc_dup_x1:
          insertByOffsetEx(-2, stack, exprlist, -1);
          break;
        case opc_dup_x2:
          if (stack.getByOffset(-2).getExprType().stackSize == 2) {
            insertByOffsetEx(-2, stack, exprlist, -1);
          }
          else {
            insertByOffsetEx(-3, stack, exprlist, -1);
          }
          break;
        case opc_dup2:
          if (stack.getByOffset(-1).getExprType().stackSize == 2) {
            pushEx(stack, exprlist, stack.getByOffset(-1).copy());
          }
          else {
            pushEx(stack, exprlist, stack.getByOffset(-2).copy());
            pushEx(stack, exprlist, stack.getByOffset(-2).copy());
          }
          break;
        case opc_dup2_x1:
          if (stack.getByOffset(-1).getExprType().stackSize == 2) {
            insertByOffsetEx(-2, stack, exprlist, -1);
          }
          else {
            insertByOffsetEx(-3, stack, exprlist, -2);
            insertByOffsetEx(-3, stack, exprlist, -1);
          }
          break;
        case opc_dup2_x2:
          if (stack.getByOffset(-1).getExprType().stackSize == 2) {
            if (stack.getByOffset(-2).getExprType().stackSize == 2) {
              insertByOffsetEx(-2, stack, exprlist, -1);
            }
            else {
              insertByOffsetEx(-3, stack, exprlist, -1);
            }
          }
          else {
            if (stack.getByOffset(-3).getExprType().stackSize == 2) {
              insertByOffsetEx(-3, stack, exprlist, -2);
              insertByOffsetEx(-3, stack, exprlist, -1);
            }
            else {
              insertByOffsetEx(-4, stack, exprlist, -2);
              insertByOffsetEx(-4, stack, exprlist, -1);
            }
          }
          break;
        case opc_swap:
          insertByOffsetEx(-2, stack, exprlist, -1);
          stack.pop();
          break;
        case opc_pop:
        case opc_pop2:
          stack.pop();
      }
    }
  }

  private void pushEx(ExprentStack stack, List<Exprent> exprlist, Exprent exprent) {
    pushEx(stack, exprlist, exprent, null);
  }

  private void pushEx(ExprentStack stack, List<Exprent> exprlist, Exprent exprent, VarType vartype) {
    int varindex = VarExprent.STACK_BASE + stack.size();
    VarExprent var = new VarExprent(varindex, vartype == null ? exprent.getExprType() : vartype, varProcessor);
    var.setStack(true);

    exprlist.add(new AssignmentExprent(var, exprent, null));
    stack.push(var.copy());
  }

  private void insertByOffsetEx(int offset, ExprentStack stack, List<Exprent> exprlist, int copyoffset) {

    int base = VarExprent.STACK_BASE + stack.size();

    LinkedList<VarExprent> lst = new LinkedList<VarExprent>();

    for (int i = -1; i >= offset; i--) {
      Exprent varex = stack.pop();
      VarExprent varnew = new VarExprent(base + i + 1, varex.getExprType(), varProcessor);
      varnew.setStack(true);
      exprlist.add(new AssignmentExprent(varnew, varex, null));
      lst.add(0, (VarExprent)varnew.copy());
    }

    Exprent exprent = lst.get(lst.size() + copyoffset).copy();
    VarExprent var = new VarExprent(base + offset, exprent.getExprType(), varProcessor);
    var.setStack(true);
    exprlist.add(new AssignmentExprent(var, exprent, null));
    lst.add(0, (VarExprent)var.copy());

    for (VarExprent expr : lst) {
      stack.push(expr);
    }
  }

  public static String getTypeName(VarType type) {
    return getTypeName(type, true);
  }

  public static String getTypeName(VarType type, boolean getShort) {

    int tp = type.type;
    if (tp <= CodeConstants.TYPE_BOOLEAN) {
      return typeNames[tp];
    }
    else if (tp == CodeConstants.TYPE_UNKNOWN) {
      return UNKNOWN_TYPE_STRING; // INFO: should not occur
    }
    else if (tp == CodeConstants.TYPE_NULL) {
      return NULL_TYPE_STRING; // INFO: should not occur
    }
    else if (tp == CodeConstants.TYPE_VOID) {
      return "void";
    }
    else if (tp == CodeConstants.TYPE_GENVAR && type.isGeneric()) {
        return type.value;
    }
    else if (tp == CodeConstants.TYPE_OBJECT) {
      if (type.isGeneric()) {
        return ((GenericType)type).getCastName();
      }

      String ret = buildJavaClassName(type.value);
      if (getShort) {
        ret = DecompilerContext.getImportCollector().getShortName(ret);
      }

      if (ret == null) {
        // FIXME: a warning should be logged
        ret = UNDEFINED_TYPE_STRING;
      }
      return ret;
    }

    throw new RuntimeException("invalid type");
  }

  public static String getCastTypeName(VarType type) {
    return getCastTypeName(type, true);
  }

  public static String getCastTypeName(VarType type, boolean getShort) {
    String s = getTypeName(type, getShort);
    int dim = type.arrayDim;
    while (dim-- > 0) {
      s += "[]";
    }
    return s;
  }

  public static PrimitiveExprsList getExpressionData(VarExprent var) {
    PrimitiveExprsList prlst = new PrimitiveExprsList();
    VarExprent vartmp = new VarExprent(VarExprent.STACK_BASE, var.getExprType(), var.getProcessor());
    vartmp.setStack(true);

    prlst.getLstExprents().add(new AssignmentExprent(vartmp, var.copy(), null));
    prlst.getStack().push(vartmp.copy());
    return prlst;
  }

  public static boolean endsWithSemikolon(Exprent expr) {
    int type = expr.type;
    return !(type == Exprent.EXPRENT_SWITCH ||
             type == Exprent.EXPRENT_MONITOR ||
             type == Exprent.EXPRENT_IF ||
             (type == Exprent.EXPRENT_VAR && ((VarExprent)expr)
               .isClassDef()));
  }

  private static void addDeletedGotoInstructionMapping(Statement stat, BytecodeMappingTracer tracer) {
    if (stat instanceof BasicBlockStatement) {
      BasicBlock block = ((BasicBlockStatement)stat).getBlock();
      List<Integer> offsets = block.getInstrOldOffsets();
      if (!offsets.isEmpty() && offsets.size() > block.getSeq().length()) { // some instructions have been deleted, but we still have offsets
        tracer.addMapping(offsets.get(offsets.size() - 1)); // add the last offset
      }
    }
  }

  public static TextBuffer jmpWrapper(Statement stat, int indent, boolean semicolon, BytecodeMappingTracer tracer) {
    TextBuffer buf = stat.toJava(indent, tracer);

    List<StatEdge> lstSuccs = stat.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL);
    if (lstSuccs.size() == 1) {
      StatEdge edge = lstSuccs.get(0);
      if (edge.getType() != StatEdge.TYPE_REGULAR && edge.explicit && edge.getDestination().type != Statement.TYPE_DUMMYEXIT) {
        buf.appendIndent(indent);

        switch (edge.getType()) {
          case StatEdge.TYPE_BREAK:
            addDeletedGotoInstructionMapping(stat, tracer);
            buf.append("break");
            break;
          case StatEdge.TYPE_CONTINUE:
            addDeletedGotoInstructionMapping(stat, tracer);
            buf.append("continue");
        }

        if (edge.labeled) {
          buf.append(" label").append(edge.closure.getStartEndRange().start);
        }
        buf.append(";").appendLineSeparator();
        tracer.incrementCurrentSourceLine();
      }
    }

    if (buf.length() == 0 && semicolon) {
      buf.appendIndent(indent).append(";").appendLineSeparator();
      tracer.incrementCurrentSourceLine();
    }

    return buf;
  }

  public static String buildJavaClassName(String name) {
    String res = name.replace('/', '.');

    if (res.contains("$")) { // attempt to invoke foreign member
      // classes correctly
      StructClass cl = DecompilerContext.getStructContext().getClass(name);
      if (cl == null || !cl.isOwn()) {
        res = res.replace('$', '.');
      }
    }

    return res;
  }

  public static TextBuffer listToJava(List<Exprent> lst, int indent, BytecodeMappingTracer tracer) {
    if (lst == null || lst.isEmpty()) {
      return new TextBuffer();
    }

    TextBuffer buf = new TextBuffer();
    lst = Exprent.sortIndexed(lst);

    for (Exprent expr : lst) {
      expr.getInferredExprType(null);
      TextBuffer content = expr.toJava(indent, tracer);
      if (content.length() > 0) {
        if (expr.type != Exprent.EXPRENT_VAR || !((VarExprent)expr).isClassDef()) {
          buf.appendIndent(indent);
        }
        buf.append(content);
        if (expr.type == Exprent.EXPRENT_MONITOR && ((MonitorExprent)expr).getMonType() == MonitorExprent.MONITOR_ENTER) {
          buf.append("{}"); // empty synchronized block
        }
        if (endsWithSemikolon(expr)) {
          buf.append(";");
        }
        buf.appendLineSeparator();
        tracer.incrementCurrentSourceLine();
      }
    }

    return buf;
  }

  public static ConstExprent getDefaultArrayValue(VarType arrtype) {

    ConstExprent defaultval;
    if (arrtype.type == CodeConstants.TYPE_OBJECT || arrtype.arrayDim > 0) {
      defaultval = new ConstExprent(VarType.VARTYPE_NULL, null, null);
    }
    else if (arrtype.type == CodeConstants.TYPE_FLOAT) {
      defaultval = new ConstExprent(VarType.VARTYPE_FLOAT, new Float(0), null);
    }
    else if (arrtype.type == CodeConstants.TYPE_LONG) {
      defaultval = new ConstExprent(VarType.VARTYPE_LONG, new Long(0), null);
    }
    else if (arrtype.type == CodeConstants.TYPE_DOUBLE) {
      defaultval = new ConstExprent(VarType.VARTYPE_DOUBLE, new Double(0), null);
    }
    else { // integer types
      defaultval = new ConstExprent(0, true, null);
    }

    return defaultval;
  }

  public static boolean getCastedExprent(Exprent exprent,
                                         VarType leftType,
                                         TextBuffer buffer,
                                         int indent,
                                         boolean castNull,
                                         BytecodeMappingTracer tracer) {
    return getCastedExprent(exprent, leftType, buffer, indent, castNull, false, tracer);
  }

  public static boolean getCastedExprent(Exprent exprent,
                                         VarType leftType,
                                         TextBuffer buffer,
                                         int indent,
                                         boolean castNull,
                                         boolean castAlways,
                                         BytecodeMappingTracer tracer) {

    VarType rightType = exprent.getInferredExprType(leftType);

    TextBuffer res = exprent.toJava(indent, tracer);

    boolean cast =
      castAlways ||
      (!leftType.isSuperset(rightType) && (rightType.equals(VarType.VARTYPE_OBJECT) || leftType.type != CodeConstants.TYPE_OBJECT)) ||
      (castNull && rightType.type == CodeConstants.TYPE_NULL && !UNDEFINED_TYPE_STRING.equals(getTypeName(leftType))) ||
      (isIntConstant(exprent) && VarType.VARTYPE_INT.isStrictSuperset(leftType));

    if (cast) {
      if (exprent.getPrecedence() >= FunctionExprent.getPrecedence(FunctionExprent.FUNCTION_CAST)) {
        res.enclose("(", ")");
      }

      res.prepend("(" + getCastTypeName(leftType) + ") ");
    }

    buffer.append(res);

    return cast;
  }

  private static boolean isIntConstant(Exprent exprent) {

    if (exprent.type == Exprent.EXPRENT_CONST) {
      ConstExprent cexpr = (ConstExprent)exprent;
      switch (cexpr.getConstType().type) {
        case CodeConstants.TYPE_BYTE:
        case CodeConstants.TYPE_BYTECHAR:
        case CodeConstants.TYPE_SHORT:
        case CodeConstants.TYPE_SHORTCHAR:
        case CodeConstants.TYPE_INT:
          return true;
      }
    }

    return false;
  }
}
