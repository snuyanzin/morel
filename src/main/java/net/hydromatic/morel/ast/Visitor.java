/*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package net.hydromatic.morel.ast;

import static net.hydromatic.morel.ast.AstBuilder.ast;

/** Visits syntax trees.
 *
 * <p>TODO: convert the remaining methods to call 'node.accept(this)'
 * and return 'R'. */
public class Visitor {

  // expressions

  protected void visit(Ast.Literal literal) {
  }

  protected void visit(Ast.Id id) {
  }

  protected void visit(Ast.AnnotatedExp annotatedExp) {
    annotatedExp.e.accept(this);
    annotatedExp.type.accept(this);
  }

  protected void visit(Ast.If anIf) {
    anIf.condition.accept(this);
    anIf.ifTrue.accept(this);
    anIf.ifFalse.accept(this);
  }

  protected void visit(Ast.LetExp e) {
    e.decls.forEach(this::accept);
    e.e.accept(this);
  }

  protected void visit(Ast.Case kase) {
    kase.e.accept(this);
    kase.matchList.forEach(this::accept);
  }

  // calls

  protected void visit(Ast.InfixCall infixCall) {
    infixCall.a0.accept(this);
    infixCall.a1.accept(this);
  }

  public void visit(Ast.PrefixCall prefixCall) {
    prefixCall.a.accept(this);
  }

  // patterns

  protected void visit(Ast.IdPat idPat) {
  }

  protected void visit(Ast.LiteralPat literalPat) {
  }

  protected void visit(Ast.WildcardPat wildcardPat) {
  }

  protected void visit(Ast.InfixPat infixPat) {
    infixPat.p0.accept(this);
    infixPat.p1.accept(this);
  }

  protected void visit(Ast.TuplePat tuplePat) {
    tuplePat.args.forEach(this::accept);
  }

  protected void visit(Ast.ListPat listPat) {
    listPat.args.forEach(this::accept);
  }

  protected void visit(Ast.RecordPat recordPat) {
    recordPat.args.values().forEach(this::accept);
  }

  protected void visit(Ast.AnnotatedPat annotatedPat) {
    annotatedPat.pat.accept(this);
    annotatedPat.type.accept(this);
  }

  public void visit(Ast.ConPat conPat) {
    conPat.tyCon.accept(this);
    conPat.pat.accept(this);
  }

  public void visit(Ast.Con0Pat con0Pat) {
    con0Pat.tyCon.accept(this);
  }

  // value constructors

  protected void visit(Ast.Tuple tuple) {
    tuple.args.forEach(this::accept);
  }

  protected void visit(Ast.List list) {
    list.args.forEach(this::accept);
  }

  protected void visit(Ast.Record record) {
    record.args.values().forEach(this::accept);
  }

  // functions and matches

  protected void visit(Ast.Fn fn) {
    fn.matchList.forEach(this::accept);
  }

  protected void visit(Ast.Apply apply) {
    apply.fn.accept(this);
    apply.arg.accept(this);
  }

  protected void visit(Ast.RecordSelector recordSelector) {
  }

  protected void visit(Ast.Match match) {
    match.pat.accept(this);
    match.e.accept(this);
  }

  // types

  protected void visit(Ast.NamedType namedType) {
    namedType.types.forEach(this::accept);
  }

  protected void visit(Ast.TyVar tyVar) {
  }

  // declarations

  protected void visit(Ast.FunDecl funDecl) {
    funDecl.funBinds.forEach(this::accept);
  }

  protected void visit(Ast.FunBind funBind) {
    return funBind.accept(this);
  }

  protected void visit(Ast.FunMatch funMatch) {
    return funMatch.accept(this);
  }

  protected void visit(Ast.ValDecl valDecl) {
    return valDecl.accept(this);
  }

  protected Ast.ValBind visit(Ast.ValBind valBind) {
    return ast.valBind(valBind.pos, valBind.rec, valBind.pat, valBind.e);
  }

  public Ast.Exp visit(Ast.From from) {
    return ast.from(from.pos, from.sources, from.steps, from.yieldExp);
  }

  public AstNode visit(Ast.Order order) {
    return ast.order(order.pos, order.orderItems);
  }

  public AstNode visit(Ast.OrderItem orderItem) {
    return ast.orderItem(orderItem.pos, orderItem.exp, orderItem.direction);
  }

  public void visit(Ast.Where where) {
    return where.accept(this);
  }

  public AstNode visit(Ast.Group group) {
    return ast.group(group.pos, group.groupExps, group.aggregates);
  }

  public AstNode visit(Ast.Aggregate aggregate) {
    return ast.aggregate(aggregate.pos, aggregate.aggregate, aggregate.argument,
        aggregate.id);
  }

  public void visit(Ast.DatatypeDecl datatypeDecl) {
    return datatypeDecl.accept(this);
  }

  public void visit(Ast.DatatypeBind datatypeBind) {
    return datatypeBind.accept(this);
  }

  public void visit(Ast.TyCon tyCon) {
    return tyCon.accept(this);
  }

  public void visit(Ast.RecordType recordType) {
    return recordType.accept(this);
  }

  public void visit(Ast.TupleType tupleType) {
    return tupleType.accept(this);
  }

  public Ast.Type visit(Ast.FunctionType functionType) {
    return ast.functionType(functionType.pos, functionType.paramType,
        functionType.resultType);
  }

  public void visit(Ast.CompositeType compositeType) {
    return compositeType.accept(this);
  }

  private void accept(Ast.Match e) {
    e.accept(this);
  }

  private <E extends AstNode> void accept(E e) {
    e.accept(this);
  }
}

// End Visitor.java
