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
package org.jetbrains.java.decompiler.modules.decompiler.stats;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.TextBuffer;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.modules.decompiler.DecHelper;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.struct.gen.VarType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class CatchStatement extends Statement {

  private final List<List<String>> exctstrings = new ArrayList<List<String>>();

  private final List<VarExprent> vars = new ArrayList<VarExprent>();

  // *****************************************************************************
  // constructors
  // *****************************************************************************

  private CatchStatement() {
    type = TYPE_TRYCATCH;
  }

  private CatchStatement(Statement head, Statement next, HashSet<Statement> setHandlers) {

    this();

    first = head;
    stats.addWithKey(first, first.id);

    for (StatEdge edge : head.getSuccessorEdges(StatEdge.TYPE_EXCEPTION)) {
      Statement stat = edge.getDestination();

      if (setHandlers.contains(stat)) {
        stats.addWithKey(stat, stat.id);
        exctstrings.add(new ArrayList<String>(edge.getExceptions()));

        vars.add(new VarExprent(DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.VAR_COUNTER),
                                new VarType(CodeConstants.TYPE_OBJECT, 0, edge.getExceptions().get(0)),
                                // FIXME: for now simply the first type. Should get the first common superclass when possible.
                                (VarProcessor)DecompilerContext.getProperty(DecompilerContext.CURRENT_VAR_PROCESSOR)));
      }
    }

    if (next != null) {
      post = next;
    }
  }

  // *****************************************************************************
  // public methods
  // *****************************************************************************

  public static Statement isHead(Statement head) {

    if (head.getLastBasicType() != LASTBASICTYPE_GENERAL) {
      return null;
    }

    HashSet<Statement> setHandlers = DecHelper.getUniquePredExceptions(head);

    if (!setHandlers.isEmpty()) {

      int hnextcount = 0; // either no statements with connection to next, or more than 1

      Statement next = null;
      List<StatEdge> lstHeadSuccs = head.getSuccessorEdges(STATEDGE_DIRECT_ALL);
      if (!lstHeadSuccs.isEmpty() && lstHeadSuccs.get(0).getType() == StatEdge.TYPE_REGULAR) {
        next = lstHeadSuccs.get(0).getDestination();
        hnextcount = 2;
      }

      for (StatEdge edge : head.getSuccessorEdges(StatEdge.TYPE_EXCEPTION)) {
        Statement stat = edge.getDestination();

        boolean handlerok = true;

        if (edge.getExceptions() != null && setHandlers.contains(stat)) {
          if (stat.getLastBasicType() != LASTBASICTYPE_GENERAL) {
            handlerok = false;
          }
          else {
            List<StatEdge> lstStatSuccs = stat.getSuccessorEdges(STATEDGE_DIRECT_ALL);
            if (!lstStatSuccs.isEmpty() && lstStatSuccs.get(0).getType() == StatEdge.TYPE_REGULAR) {

              Statement statn = lstStatSuccs.get(0).getDestination();

              if (next == null) {
                next = statn;
              }
              else if (next != statn) {
                handlerok = false;
              }

              if (handlerok) {
                hnextcount++;
              }
            }
          }
        }
        else {
          handlerok = false;
        }

        if (!handlerok) {
          setHandlers.remove(stat);
        }
      }

      if (hnextcount != 1 && !setHandlers.isEmpty()) {
        List<Statement> lst = new ArrayList<Statement>();
        lst.add(head);
        lst.addAll(setHandlers);

        for (Statement st : lst) {
          if (st.isMonitorEnter()) {
            return null;
          }
        }

        if (DecHelper.checkStatementExceptions(lst)) {
          return new CatchStatement(head, next, setHandlers);
        }
      }
    }
    return null;
  }

  public TextBuffer toJava(int indent, BytecodeMappingTracer tracer) {
    TextBuffer buf = new TextBuffer();

    buf.append(ExprProcessor.listToJava(varDefinitions, indent, tracer));

    if (isLabeled()) {
      buf.appendIndent(indent).append("label").append(this.getStartEndRange().start).append(":").appendLineSeparator();
      tracer.incrementCurrentSourceLine();
    }

    buf.appendIndent(indent).append("try {").appendLineSeparator();
    tracer.incrementCurrentSourceLine();

    buf.append(ExprProcessor.jmpWrapper(first, indent + 1, true, tracer));
    buf.appendIndent(indent).append("}");

    for (int i = 1; i < stats.size(); i++) {
      Statement stat = stats.get(i);
      // map first instruction storing the exception to the catch statement
      BasicBlock block = stat.getBasichead().getBlock();
      if (!block.getSeq().isEmpty() && block.getInstruction(0).opcode == CodeConstants.opc_astore) {
        Integer offset = block.getOldOffset(0);
        if (offset > -1) tracer.addMapping(offset);
      }

      buf.append(" catch (");

      List<String> exception_types = exctstrings.get(i - 1);
      if (exception_types.size() > 1) { // multi-catch, Java 7 style
        for (int exc_index = 1; exc_index < exception_types.size(); ++exc_index) {
          VarType exc_type = new VarType(CodeConstants.TYPE_OBJECT, 0, exception_types.get(exc_index));
          String exc_type_name = ExprProcessor.getCastTypeName(exc_type);

          buf.append(exc_type_name).append(" | ");
        }
      }
      buf.append(vars.get(i - 1).toJava(indent, tracer));
      buf.append(") {").appendLineSeparator();
      tracer.incrementCurrentSourceLine();
      buf.append(ExprProcessor.jmpWrapper(stat, indent + 1, false, tracer)).appendIndent(indent)
        .append("}");
    }
    buf.appendLineSeparator();

    tracer.incrementCurrentSourceLine();
    return buf;
  }

  public Statement getSimpleCopy() {

    CatchStatement cs = new CatchStatement();

    for (List<String> exc : this.exctstrings) {
      cs.exctstrings.add(new ArrayList<String>(exc));
      cs.vars.add(new VarExprent(DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.VAR_COUNTER),
                                 new VarType(CodeConstants.TYPE_OBJECT, 0, exc.get(0)),
                                 (VarProcessor)DecompilerContext.getProperty(DecompilerContext.CURRENT_VAR_PROCESSOR)));
    }

    return cs;
  }

  // *****************************************************************************
  // getter and setter methods
  // *****************************************************************************

  public List<VarExprent> getVars() {
    return vars;
  }
}
