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

import org.jetbrains.java.decompiler.code.SwitchInstruction;
import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.TextBuffer;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.modules.decompiler.DecHelper;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.exps.ArrayExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.ConstExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.FieldExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.InvocationExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.SwitchExprent;
import org.jetbrains.java.decompiler.modules.decompiler.vars.StartEndPair;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.gen.VarType;

import java.util.*;

public class SwitchStatement extends Statement {

  // *****************************************************************************
  // private fields
  // *****************************************************************************

  private List<Statement> caseStatements = new ArrayList<Statement>();

  private List<List<StatEdge>> caseEdges = new ArrayList<List<StatEdge>>();

  private List<List<ConstExprent>> caseValues = new ArrayList<List<ConstExprent>>();

  private StatEdge default_edge;

  private final List<Exprent> headexprent = new ArrayList<Exprent>();

  // *****************************************************************************
  // constructors
  // *****************************************************************************

  private SwitchStatement() {
    type = TYPE_SWITCH;

    headexprent.add(null);
  }

  private SwitchStatement(Statement head, Statement poststat) {

    this();

    first = head;
    stats.addWithKey(head, head.id);

    // find post node
    Set<Statement> lstNodes = new HashSet<Statement>(head.getNeighbours(StatEdge.TYPE_REGULAR, DIRECTION_FORWARD));

    // cluster nodes
    if (poststat != null) {
      post = poststat;
      lstNodes.remove(post);
    }

    default_edge = head.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL).get(0);

    // We need to use set above in case we have multiple edges to the same node. But HashSets iterator is not ordered, so sort
    List<Statement> sorted = new ArrayList<>(lstNodes);
    Collections.sort(sorted, new Comparator<Statement>() {
      @Override
      public int compare(Statement o1, Statement o2) {
        return o1.id - o2.id;
      }
    });
    for (Statement st : sorted) {
      stats.addWithKey(st, st.id);
    }
  }

  // *****************************************************************************
  // public methods
  // *****************************************************************************

  public static Statement isHead(Statement head) {

    if (head.type == Statement.TYPE_BASICBLOCK && head.getLastBasicType() == Statement.LASTBASICTYPE_SWITCH) {

      List<Statement> lst = new ArrayList<Statement>();
      if (DecHelper.isChoiceStatement(head, lst)) {
        Statement post = lst.remove(0);

        for (Statement st : lst) {
          if (st.isMonitorEnter()) {
            return null;
          }
        }

        if (DecHelper.checkStatementExceptions(lst)) {
          return new SwitchStatement(head, post);
        }
      }
    }

    return null;
  }

  public TextBuffer toJava(int indent, BytecodeMappingTracer tracer) {
    TextBuffer buf = new TextBuffer();
    buf.append(ExprProcessor.listToJava(varDefinitions, indent, tracer));
    buf.append(first.toJava(indent, tracer));

    if (isLabeled()) {
      buf.appendIndent(indent).append("label").append(this.getStartEndRange().start).append(":").appendLineSeparator();
      tracer.incrementCurrentSourceLine();
    }

    //Doesn't seem to be a better place to put it so enhance things here
    Map<Integer, String> remaps = enhanceHead(headexprent.get(0), buf, indent, tracer);

    if (remaps == null) {
      buf.appendIndent(indent).append(headexprent.get(0).toJava(indent, tracer)).append(" {").appendLineSeparator();
    }
    tracer.incrementCurrentSourceLine();

    VarType switch_type = headexprent.get(0).getExprType();

    for (int i = 0; i < caseStatements.size(); i++) {

      Statement stat = caseStatements.get(i);
      List<StatEdge> edges = caseEdges.get(i);
      List<ConstExprent> values = caseValues.get(i);

      for (int j = 0; j < edges.size(); j++) {
        if (edges.get(j) == default_edge) {
          buf.appendIndent(indent + 1).append("default:").appendLineSeparator();
          tracer.incrementCurrentSourceLine();
        }
        else {
          ConstExprent value = (ConstExprent)values.get(j).copy();
          value.setConstType(switch_type);

          buf.appendIndent(indent + 1).append("case ");
          if (remaps == null) {
            buf.append(value.toJava(indent, tracer));
          }
          else {
            buf.append(remaps.get(value.getValue()));
          }
          buf.append(":").appendLineSeparator();
          tracer.incrementCurrentSourceLine();
        }
      }

      buf.append(ExprProcessor.jmpWrapper(stat, indent + 2, false, tracer));
    }

    buf.appendIndent(indent).append("}").appendLineSeparator();
    tracer.incrementCurrentSourceLine();

    return buf;
  }

  private Map<Integer, String> enhanceHead(Exprent exprent, TextBuffer buf, int indent, BytecodeMappingTracer tracer) {

    if (exprent.type != Exprent.EXPRENT_SWITCH) return null;

    SwitchExprent swtch = (SwitchExprent)exprent;
    if (swtch.getValue().type != Exprent.EXPRENT_ARRAY) return null;

    ArrayExprent array = (ArrayExprent)swtch.getValue();
    if (array.getArray().type != Exprent.EXPRENT_FIELD || array.getIndex().type != Exprent.EXPRENT_INVOCATION) return null;

    FieldExprent field = (FieldExprent)array.getArray();
    InvocationExprent invoc = (InvocationExprent)array.getIndex();
    StructClass cls = DecompilerContext.getStructContext().getClass(field.getClassname());
    if (cls == null || !field.isStatic() || !"ordinal".equals(invoc.getName()) || !"()I".equals(invoc.getStringDescriptor())) return null;

    Map<Integer, String> ret = cls.enumSwitchMap.get(field.getName());
    if (ret == null) return null;

    for (List<ConstExprent> lst : getCaseValues()) {
      if (lst != null) {
        for (ConstExprent cst : lst) {
          if (cst != null && (!(cst.getValue() instanceof Integer) || !ret.containsKey(cst.getValue()))) {
            return null;
          }
        }
      }
    }

    tracer.addMapping(swtch.bytecode);
    tracer.addMapping(field.bytecode);
    tracer.addMapping(invoc.bytecode);
    buf.appendIndent(indent).append((invoc.getInstance().toJava(indent, tracer).enclose("switch (", ")"))).append(" {").appendLineSeparator();
    return ret;
  }

  public void initExprents() {
    SwitchExprent swexpr = (SwitchExprent)first.getExprents().remove(first.getExprents().size() - 1);
    swexpr.setCaseValues(caseValues);

    headexprent.set(0, swexpr);
  }

  public List<Object> getSequentialObjects() {

    List<Object> lst = new ArrayList<Object>(stats);
    lst.add(1, headexprent.get(0));

    return lst;
  }

  public void replaceExprent(Exprent oldexpr, Exprent newexpr) {
    if (headexprent.get(0) == oldexpr) {
      headexprent.set(0, newexpr);
    }
  }

  public void replaceStatement(Statement oldstat, Statement newstat) {

    for (int i = 0; i < caseStatements.size(); i++) {
      if (caseStatements.get(i) == oldstat) {
        caseStatements.set(i, newstat);
      }
    }

    super.replaceStatement(oldstat, newstat);
  }

  public Statement getSimpleCopy() {
    return new SwitchStatement();
  }

  public void initSimpleCopy() {
    first = stats.get(0);
    default_edge = first.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL).get(0);

    sortEdgesAndNodes();
  }

  // *****************************************************************************
  // private methods
  // *****************************************************************************

  public void sortEdgesAndNodes() {

    HashMap<StatEdge, Integer> mapEdgeIndex = new HashMap<StatEdge, Integer>();

    List<StatEdge> lstFirstSuccs = first.getSuccessorEdges(STATEDGE_DIRECT_ALL);
    for (int i = 0; i < lstFirstSuccs.size(); i++) {
      mapEdgeIndex.put(lstFirstSuccs.get(i), i == 0 ? lstFirstSuccs.size() : i);
    }

    // case values
    BasicBlockStatement bbstat = (BasicBlockStatement)first;
    int[] values = ((SwitchInstruction)bbstat.getBlock().getLastInstruction()).getValues();

    List<Statement> nodes = new ArrayList<Statement>();
    List<List<Integer>> edges = new ArrayList<List<Integer>>();

    // collect regular edges
    for (int i = 1; i < stats.size(); i++) {

      Statement stat = stats.get(i);

      List<Integer> lst = new ArrayList<Integer>();
      for (StatEdge edge : stat.getPredecessorEdges(StatEdge.TYPE_REGULAR)) {
        if (edge.getSource() == first) {
          lst.add(mapEdgeIndex.get(edge));
        }
      }
      Collections.sort(lst);

      nodes.add(stat);
      edges.add(lst);
    }

    // collect exit edges
    List<StatEdge> lstExitEdges = first.getSuccessorEdges(StatEdge.TYPE_BREAK | StatEdge.TYPE_CONTINUE);
    while (!lstExitEdges.isEmpty()) {
      StatEdge edge = lstExitEdges.get(0);

      List<Integer> lst = new ArrayList<Integer>();
      for (int i = lstExitEdges.size() - 1; i >= 0; i--) {
        StatEdge edgeTemp = lstExitEdges.get(i);
        if (edgeTemp.getDestination() == edge.getDestination() && edgeTemp.getType() == edge.getType()) {
          lst.add(mapEdgeIndex.get(edgeTemp));
          lstExitEdges.remove(i);
        }
      }
      Collections.sort(lst);

      nodes.add(null);
      edges.add(lst);
    }

    // sort edges (bubblesort)
    for (int i = 0; i < edges.size() - 1; i++) {
      for (int j = edges.size() - 1; j > i; j--) {
        if (edges.get(j - 1).get(0) > edges.get(j).get(0)) {
          edges.set(j, edges.set(j - 1, edges.get(j)));
          nodes.set(j, nodes.set(j - 1, nodes.get(j)));
        }
      }
    }

    // sort statement cliques
    for (int index = 0; index < nodes.size(); index++) {
      Statement stat = nodes.get(index);

      if (stat != null) {
        HashSet<Statement> setPreds = new HashSet<Statement>(stat.getNeighbours(StatEdge.TYPE_REGULAR, DIRECTION_BACKWARD));
        setPreds.remove(first);

        if (!setPreds.isEmpty()) {
          Statement pred =
            setPreds.iterator().next(); // assumption: at most one predecessor node besides the head. May not hold true for obfuscated code.
          for (int j = 0; j < nodes.size(); j++) {
            if (j != (index - 1) && nodes.get(j) == pred) {
              nodes.add(j + 1, stat);
              edges.add(j + 1, edges.get(index));

              if (j > index) {
                nodes.remove(index);
                edges.remove(index);
                index--;
              }
              else {
                nodes.remove(index + 1);
                edges.remove(index + 1);
              }
              break;
            }
          }
        }
      }
    }

    // translate indices back into edges
    List<List<StatEdge>> lstEdges = new ArrayList<List<StatEdge>>();
    List<List<ConstExprent>> lstValues = new ArrayList<List<ConstExprent>>();

    for (List<Integer> lst : edges) {
      List<StatEdge> lste = new ArrayList<StatEdge>();
      List<ConstExprent> lstv = new ArrayList<ConstExprent>();

      List<StatEdge> lstSuccs = first.getSuccessorEdges(STATEDGE_DIRECT_ALL);
      for (Integer in : lst) {
        int index = in == lstSuccs.size() ? 0 : in;

        lste.add(lstSuccs.get(index));
        lstv.add(index == 0 ? null : new ConstExprent(values[index - 1], false, null));
      }
      lstEdges.add(lste);
      lstValues.add(lstv);
    }

    // replace null statements with dummy basic blocks
    for (int i = 0; i < nodes.size(); i++) {
      if (nodes.get(i) == null) {
        BasicBlockStatement bstat = new BasicBlockStatement(new BasicBlock(
          DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.STATEMENT_COUNTER)));

        StatEdge sample_edge = lstEdges.get(i).get(0);

        bstat.addSuccessor(new StatEdge(sample_edge.getType(), bstat, sample_edge.getDestination(), sample_edge.closure));

        for (StatEdge edge : lstEdges.get(i)) {

          edge.getSource().changeEdgeType(DIRECTION_FORWARD, edge, StatEdge.TYPE_REGULAR);
          edge.closure.getLabelEdges().remove(edge);

          edge.getDestination().removePredecessor(edge);
          edge.getSource().changeEdgeNode(DIRECTION_FORWARD, edge, bstat);
          bstat.addPredecessor(edge);
        }

        nodes.set(i, bstat);
        stats.addWithKey(bstat, bstat.id);
        bstat.setParent(this);
      }
    }

    caseStatements = nodes;
    caseEdges = lstEdges;
    caseValues = lstValues;
  }

  public List<Exprent> getHeadexprentList() {
    return headexprent;
  }

  public Exprent getHeadexprent() {
    return headexprent.get(0);
  }

  public List<List<StatEdge>> getCaseEdges() {
    return caseEdges;
  }

  public List<Statement> getCaseStatements() {
    return caseStatements;
  }

  public StatEdge getDefault_edge() {
    return default_edge;
  }

  public List<List<ConstExprent>> getCaseValues() {
    return caseValues;
  }

  @Override
  public StartEndPair getStartEndRange() {
    StartEndPair[] sepairs = new StartEndPair[caseStatements.size() + 1];
    int i = 0;
    sepairs[i++] = super.getStartEndRange();
    for (Statement st : caseStatements) {
      sepairs[i++] = st.getStartEndRange();
    }
    return StartEndPair.join(sepairs);
  }
}
