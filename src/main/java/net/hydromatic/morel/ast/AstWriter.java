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

import net.hydromatic.morel.compile.BuiltIn;

import com.google.common.collect.Lists;

import java.math.BigDecimal;
import java.util.List;

/** Context for writing an AST out as a string. */
public class AstWriter {
  private final StringBuilder b = new StringBuilder();

  /** {@inheritDoc}
   *
   * <p>Returns the ML source code generated by this writer. */
  @Override public String toString() {
    return b.toString();
  }

  /** Appends a string to the output. */
  public AstWriter append(String s) {
    b.append(s);
    return this;
  }

  /** Appends an identifier to the output. */
  public AstWriter id(String s) {
    b.append(s);
    return this;
  }

  /** Appends a ordinal-qualified-identifier to the output.
   *
   * <p>Prints "v" for {@code id("v", 0)}, "v#1" for {@code id("v", 1)},
   * and so forth. */
  public AstWriter id(String s, int i) {
    b.append(s);
    if (i > 0) {
      b.append('#').append(i);
    }
    return this;
  }

  /** Appends a call to an infix operator. */
  public AstWriter infix(int left, AstNode a0, Op op, AstNode a1, int right) {
    if (op == Op.APPLY && a0.op == Op.ID) {
      if (a0 instanceof Ast.Id) {
        final Op op2 = Op.BY_OP_NAME.get(((Ast.Id) a0).name);
        if (op2 != null && op2.left > 0) {
          final List<Ast.Exp> args = ((Ast.Tuple) a1).args;
          final Ast.InfixCall call =
              new Ast.InfixCall(Pos.ZERO, op2, args.get(0), args.get(1));
          return call.unparse(this, left, right);
        }
      }
      if (a0 instanceof Core.Id) {
        // TODO: obsolete Core.Id for these purposes. The operator should
        // be a function literal, and we would use a reverse mapping to
        // figure out which built-in operator it implements, and whether it
        // is infix (e.g. "+") or in a namespace (e.g. "#translate String")
        final Op op2 = Op.BY_OP_NAME.get(((Core.Id) a0).idPat);
        if (op2 != null && op2.left > 0) {
          final List<Core.Exp> args = ((Core.Tuple) a1).args;
          return infix(left, args.get(0), op2, args.get(1), right);
        }
      }
    }
    if (left > op.left || op.right < right) {
      return append("(").infix(0, a0, op, a1, 0).append(")");
    }
    append(a0, left, op.left);
    append(op.padded);
    append(a1, op.right, right);
    return this;
  }

  /** Appends a call to an prefix operator. */
  public AstWriter prefix(int left, Op op, AstNode a, int right) {
    if (left > op.left || op.right < right) {
      return append("(").prefix(0, op, a, 0).append(")");
    }
    append(op.padded);
    a.unparse(this, op.right, right);
    return this;
  }

  /** Appends a call to a binary operator (e.g. "val ... = ..."). */
  public AstWriter binary(String left, AstNode a0, String mid, AstNode a1,
      int right) {
    append(left);
    a0.unparse(this, 0, 0);
    append(mid);
    a1.unparse(this, 0, right);
    return this;
  }

  /** Appends a call to a binary operator (e.g. "let ... in ... end"). */
  public AstWriter binary(String left, AstNode a0, String mid, AstNode a1,
      String right) {
    append(left);
    a0.unparse(this, 0, 0);
    append(mid);
    a1.unparse(this, 0, 0);
    append(right);
    return this;
  }

  /** Appends a parse tree node. */
  public AstWriter append(AstNode node, int left, int right) {
    if (left > node.op.left || node.op.right < right) {
      append("(");
      node.unparse(this, 0, 0);
      append(")");
    } else {
      node.unparse(this, left, right);
    }
    return this;
  }

  /** Appends a list of parse tree nodes. */
  public AstWriter appendAll(Iterable<? extends AstNode> nodes, int left, Op op,
      int right) {
    @SuppressWarnings("unchecked")
    final List<AstNode> nodeList = nodes instanceof List
        ? (List) nodes : Lists.newArrayList(nodes);
    for (int i = 0; i < nodeList.size(); i++) {
      final AstNode node = nodeList.get(i);
      final int thisLeft = i == 0 ? left : op.left;
      final int thisRight = i == nodeList.size() - 1 ? right : op.right;
      if (i > 0) {
        append(op.padded);
      }
      append(node, thisLeft, thisRight);
    }
    return this;
  }

  /** Appends a list of parse tree nodes separated by {@code sep}. */
  public AstWriter appendAll(Iterable<? extends AstNode> list, String sep) {
    return appendAll(list, "", sep, "");
  }

  /** Appends a list of parse tree nodes separated by {@code sep}, and also with
   * prefix and suffix: {@code start node0 sep node1 ... sep nodeN end}. */
  public AstWriter appendAll(Iterable<? extends AstNode> list, String start,
      String sep, String end) {
    return appendAll(list, start, sep, end, "");
  }

  /** Appends a list of parse tree nodes separated by {@code sep}, and also with
   * prefix and suffix: {@code start node0 sep node1 ... sep nodeN end}. */
  public AstWriter appendAll(Iterable<? extends AstNode> list, String start,
      String sep, String end, String empty) {
    String s = start;
    int i = 0;
    for (AstNode node : list) {
      ++i;
      append(s);
      s = sep;
      append(node, 0, 0);
    }
    if (i == 0 && empty != null) {
      append(empty);
    } else {
      append(end);
    }
    return this;
  }

  public AstWriter appendLiteral(Comparable value) {
    if (value instanceof String) {
      append("\"")
          .append(((String) value).replaceAll("\"", "\\\""))
          .append("\"");
    } else if (value instanceof Character) {
      final Character c = (Character) value;
      append("#\"")
          .append(c == '"' ? "\\\"" : c.toString())
          .append("\"");
    } else if (value instanceof BigDecimal) {
      BigDecimal c = (BigDecimal) value;
      if (c.compareTo(BigDecimal.ZERO) < 0) {
        append("~");
        c = c.negate();
      }
      append(c.toString());
    } else if (value instanceof BuiltIn) {
      append(((BuiltIn) value).mlName);
    } else {
      append(value.toString());
    }
    return this;
  }
}

// End AstWriter.java
