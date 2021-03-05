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
package net.hydromatic.morel.compile;

import org.apache.calcite.DataContext;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.EnumerableDefaults;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.externalize.RelJson;
import org.apache.calcite.rel.externalize.RelJsonReader;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.TableFunction;
import org.apache.calcite.schema.impl.TableFunctionImpl;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.SqlOperandMetadata;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlUserDefinedTableFunction;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.JsonBuilder;
import org.apache.calcite.util.Util;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.EvalEnvs;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.parse.ParseException;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nonnull;

import static net.hydromatic.morel.ast.AstBuilder.ast;

/** Compiles an expression to code that can be evaluated. */
public class CalciteCompiler extends Compiler {
  /** Morel operators and their exact equivalents in Calcite. */
  static final Map<String, SqlOperator> DIRECT_OPS =
      ImmutableMap.<String, SqlOperator>builder()
          .put("op =", SqlStdOperatorTable.EQUALS)
          .put("op <>", SqlStdOperatorTable.NOT_EQUALS)
          .put("op <", SqlStdOperatorTable.LESS_THAN)
          .put("op <=", SqlStdOperatorTable.LESS_THAN_OR_EQUAL)
          .put("op >", SqlStdOperatorTable.GREATER_THAN)
          .put("op >=", SqlStdOperatorTable.GREATER_THAN_OR_EQUAL)
          .put("op +", SqlStdOperatorTable.PLUS)
          .put("op -", SqlStdOperatorTable.MINUS)
          .put("op ~", SqlStdOperatorTable.UNARY_MINUS)
          .put("op *", SqlStdOperatorTable.MULTIPLY)
          .put("op /", SqlStdOperatorTable.DIVIDE)
          .put("op mod", SqlStdOperatorTable.MOD)
          .build();

  static final Map<Op, SqlOperator> INFIX_OPERATORS =
      ImmutableMap.<Op, SqlOperator>builder()
          .put(Op.ANDALSO, SqlStdOperatorTable.AND)
          .put(Op.ORELSE, SqlStdOperatorTable.OR)
          .build();

  public CalciteCompiler(TypeMap typeMap) {
    super(typeMap);
  }

  public RelNode toRel(Environment env, Ast.Exp expression) {
    final FrameworkConfig config = Frameworks.newConfigBuilder().build();
    final RelBuilder relBuilder = RelBuilder.create(config);
    final RelContext cx = new RelContext(env, relBuilder, ImmutableMap.of(), 0);
    toRel(cx, expression);
    return relBuilder.build();
  }

  void toRel(RelContext cx, Ast.Exp expression) {
    ((RelCode) compile(cx, expression)).toRel(cx);
  }

  @Override protected Code compileLet(Context cx, Ast.Decl decl, Ast.Exp e) {
    Code code = super.compileLet(cx, decl, e);
    return new RelCode() {
      @Override public Object eval(EvalEnv env) {
        return code.eval(env);
      }

      @Override public void toRel(RelContext cx) {
        final List<Code> varCodes = new ArrayList<>();
        final List<Binding> bindings = new ArrayList<>();
        compileDecl(cx, decl, varCodes, bindings, null);
        Context cx2 = cx.bindAll(bindings);
        final Code resultCode = compile(cx2, e);
        throw new AssertionError("TODO");
      }
    };
  }

  @Override protected RelCode compileApply(Context cx, Ast.Apply apply) {
    final Code code = super.compileApply(cx, apply);
    return new RelCode() {
      @Override public Object eval(EvalEnv env) {
        return code.eval(env);
      }

      @Override public void toRel(RelContext cx) {
        if (apply.fn instanceof Ast.RecordSelector
            && apply.arg instanceof Ast.Id) {
          // Something like '#emp scott', 'scott' is a foreign value
          final Object o = code.eval(evalEnvOf(cx.env));
          if (o instanceof RelList) {
            cx.relBuilder.push(((RelList) o).rel);
            return;
          }
        }
        if (apply.fn instanceof Ast.Id) {
          switch (((Ast.Id) apply.fn).name) {
          case "op union":
          case "op except":
          case "op intersect":
            // For example, '[1, 2, 3] union (from scott.dept yield deptno)'
            final Ast.Tuple tuple = (Ast.Tuple) apply.arg;
            tuple.forEachArg((arg, i) -> CalciteCompiler.this.toRel(cx, arg));
            harmonizeRowTypes(cx.relBuilder, tuple.args.size());
            switch (((Ast.Id) apply.fn).name) {
            case "op union":
              cx.relBuilder.union(true, tuple.args.size());
              return;
            case "op except":
              cx.relBuilder.minus(false, tuple.args.size());
              return;
            case "op intersect":
              cx.relBuilder.intersect(false, tuple.args.size());
              return;
            default:
              throw new AssertionError(apply.fn);
            }
          }
        }
        final TableFunction tableFunction =
            TableFunctionImpl.create(CalciteMorel.class, "eval0");
        final SqlIdentifier name =
            new SqlIdentifier("morel", SqlParserPos.ZERO);
        final List<Arg> args =
            ImmutableList.of(
                Arg.of("code", f -> f.createSqlType(SqlTypeName.VARCHAR),
                    SqlTypeFamily.STRING, false),
                Arg.of("typeJson", f -> f.createSqlType(SqlTypeName.VARCHAR),
                    SqlTypeFamily.STRING, false));
        final Type type = typeMap.getType(apply);
        final RelDataTypeFactory typeFactory = cx.relBuilder.getTypeFactory();
        final RelDataType calciteType =
            CalciteToMorelConverter.forMorel(type, typeFactory).calciteType;
        final RelDataType rowType = calciteType.getComponentType();
        final SqlOperator op =
            new SqlUserDefinedTableFunction(name, SqlKind.OTHER_FUNCTION,
                b -> CalciteToMorelConverter.forMorel(type, b.getTypeFactory())
                    .calciteType,
                InferTypes.ANY_NULLABLE, Arg.metadata(args),
                tableFunction);
        final JsonBuilder jsonBuilder = new JsonBuilder();
        final String jsonRowType =
            jsonBuilder.toJsonString(
                new RelJson(jsonBuilder).toJson(rowType));
        final String morelCode = apply.toString();
        final List<RexNode> operands =
            ImmutableList.of(cx.relBuilder.literal(morelCode),
                cx.relBuilder.literal(jsonRowType));
        cx.relBuilder.functionScan(op, 0, operands);
      }
    };
  }

  @Override protected Code compileList(Context cx, Ast.List list) {
    final Code code = super.compileList(cx, list);
    return new RelCode() {
      @Override public Object eval(EvalEnv env) {
        return code.eval(env);
      }

      @Override public void toRel(RelContext cx) {
        for (Ast.Exp arg : list.args) {
          cx.relBuilder.values(new String[] {"T"}, true);
          yield_(cx, arg);
        }
        cx.relBuilder.union(true, list.args.size());
      }
    };
  }

  private static void harmonizeRowTypes(RelBuilder relBuilder, int inputCount) {
    final List<RelNode> inputs = new ArrayList<>();
    for (int i = 0; i < inputCount; i++) {
      inputs.add(relBuilder.build());
    }
    final RelDataType rowType = relBuilder.getTypeFactory()
        .leastRestrictive(Util.transform(inputs, RelNode::getRowType));
    for (RelNode input : Lists.reverse(inputs)) {
      relBuilder.push(input)
          .convert(rowType, false);
    }
  }

  @Override protected RelCode compileFrom(Context cx, Ast.From from) {
    final Code code = super.compileFrom(cx, from);
    return new RelCode() {
      @Override public Object eval(EvalEnv env) {
        return code.eval(env);
      }

      @Override public void toRel(RelContext cx) {
        final Environment env = cx.env;
        final RelBuilder relBuilder = cx.relBuilder;
        final Map<Ast.Pat, RelNode> sourceCodes = new LinkedHashMap<>();
        final List<Binding> bindings = new ArrayList<>();
        for (Map.Entry<Ast.Pat, Ast.Exp> patExp : from.sources.entrySet()) {
          final RelNode expCode =
              CalciteCompiler.this.toRel(env.bindAll(bindings), patExp.getValue());
          final Ast.Pat pat0 = patExp.getKey();
          final ListType listType = (ListType) typeMap.getType(patExp.getValue());
          final Ast.Pat pat = expandRecordPattern(pat0, listType.elementType);
          sourceCodes.put(pat, expCode);
          pat.visit(p -> {
            if (p instanceof Ast.IdPat) {
              final Ast.IdPat idPat = (Ast.IdPat) p;
              bindings.add(Binding.of(idPat.name, typeMap.getType(p)));
            }
          });
        }
        final Map<String, Function<RelBuilder, RexNode>> map = new HashMap<>();
        if (sourceCodes.size() == 0) {
          relBuilder.values(new String[] {"ZERO"}, 0);
        } else {
          final SortedMap<String, VarData> varOffsets = new TreeMap<>();
          int i = 0;
          int offset = 0;
          for (Map.Entry<Ast.Pat, RelNode> pair : sourceCodes.entrySet()) {
            final Ast.Pat pat = pair.getKey();
            final RelNode r = pair.getValue();
            relBuilder.push(r);
            if (pat instanceof Ast.IdPat) {
              relBuilder.as(((Ast.IdPat) pat).name);
              final int finalOffset = offset;
              map.put(((Ast.IdPat) pat).name, b ->
                  b.getRexBuilder().makeRangeReference(r.getRowType(),
                      finalOffset, false));
              varOffsets.put(((Ast.IdPat) pat).name,
                  new VarData(typeMap.getType(pat), offset, r.getRowType()));
            }
            offset += r.getRowType().getFieldCount();
            if (++i == 2) {
              relBuilder.join(JoinRelType.INNER);
              --i;
            }
          }
          final BiMap<Integer, Integer> biMap = HashBiMap.create();
          int k = 0;
          offset = 0;
          map.clear();
          for (Map.Entry<String, VarData> entry : varOffsets.entrySet()) {
            final String var = entry.getKey();
            final VarData data = entry.getValue();
            for (int j = 0; j < data.rowType.getFieldCount(); j++) {
              biMap.put(k++, data.offset + j);
            }
            final int finalOffset = offset;
            if (data.type instanceof RecordType) {
              map.put(var, b ->
                  b.getRexBuilder().makeRangeReference(data.rowType,
                      finalOffset, false));
            } else {
              map.put(var, b -> b.field(finalOffset));
            }
            offset += data.rowType.getFieldCount();
          }
          relBuilder.project(relBuilder.fields(list(biMap)));
        }
        cx = new RelContext(env.bindAll(bindings), relBuilder, map, 1);
        for (Ast.FromStep fromStep : from.steps) {
          switch (fromStep.op) {
          case WHERE:
            cx = where(cx, (Ast.Where) fromStep);
            break;
          case ORDER:
            cx = order(cx, (Ast.Order) fromStep);
            break;
          case GROUP:
            cx = group(cx, (Ast.Group) fromStep);
            break;
          default:
            throw new AssertionError(fromStep);
          }
        }
        if (from.yieldExp != null) {
          yield_(cx, from.yieldExp);
        }
      }
    };
  }

  private ImmutableList<Integer> list(BiMap<Integer, Integer> biMap) {
    // Assume that biMap has keys 0 .. size() - 1; each key occurs once.
    final List<Integer> list =
        new ArrayList<>(Collections.nCopies(biMap.size(), null));
    biMap.forEach(list::set);
    // Will throw if there are any nulls left.
    return ImmutableList.copyOf(list);
  }

  private RelBuilder yield_(RelContext cx, Ast.Exp exp) {
    final Ast.Record record;
    switch (exp.op) {
    case ID:
      final Ast.Id id = (Ast.Id) exp;
      record = toRecord(cx, id);
      if (record != null) {
        return yield_(cx, record);
      }
      break;

    case RECORD:
      record = (Ast.Record) exp;
      return cx.relBuilder.project(
          Util.transform(record.args.values(), e -> translate(cx, e)),
          record.args.keySet());
    }
    RexNode rex = translate(cx, exp);
    return cx.relBuilder.project(rex);
  }

  private RexNode translate(RelContext cx, Ast.Exp exp) {
    final Ast.Record record;
    switch (exp.op) {
    case BOOL_LITERAL:
    case CHAR_LITERAL:
    case INT_LITERAL:
    case REAL_LITERAL:
    case STRING_LITERAL:
    case UNIT_LITERAL:
      final Ast.Literal literal = (Ast.Literal) exp;
      switch (exp.op) {
      case CHAR_LITERAL:
        // convert from Character to singleton String
        return cx.relBuilder.literal(literal.value + "");
      case UNIT_LITERAL:
        return cx.relBuilder.call(SqlStdOperatorTable.ROW);
      default:
        return cx.relBuilder.literal(literal.value);
      }

    case ID:
      // In 'from e in emps yield e', 'e' expands to a record,
      // '{e.deptno, e.ename}'
      final Ast.Id id = (Ast.Id) exp;
      final Binding binding = cx.env.getOpt(id.name);
      if (binding != null && binding.value != Unit.INSTANCE) {
        if (binding.value instanceof Boolean) {
          final Boolean b = (Boolean) binding.value;
          return translate(cx, ast.boolLiteral(id.pos, b));
        }
        if (binding.value instanceof Character) {
          final Character c = (Character) binding.value;
          return translate(cx, ast.charLiteral(id.pos, c));
        }
        if (binding.value instanceof Integer) {
          final BigDecimal bd = BigDecimal.valueOf((Integer) binding.value);
          return translate(cx, ast.intLiteral(id.pos, bd));
        }
        if (binding.value instanceof Float) {
          final BigDecimal bd = BigDecimal.valueOf((Float) binding.value);
          return translate(cx, ast.realLiteral(id.pos, bd));
        }
        if (binding.value instanceof String) {
          final String s = (String) binding.value;
          return translate(cx, ast.stringLiteral(id.pos, s));
        }
      }
      record = toRecord(cx, id);
      if (record != null) {
        return translate(cx, record);
      }
      if (cx.map.containsKey(id.name)) {
        // Not a record, so must be a scalar. It is represented in Calcite
        // as a record with one field.
        final Function<RelBuilder, RexNode> fn = cx.map.get(id.name);
        return fn.apply(cx.relBuilder);
      }
      break;

    case APPLY:
      final Ast.Apply apply = (Ast.Apply) exp;
      if (apply.fn instanceof Ast.RecordSelector
          && apply.arg instanceof Ast.Id) {
        // Something like '#deptno e',
        final RexNode range =
            cx.map.get(((Ast.Id) apply.arg).name).apply(cx.relBuilder);
        return cx.relBuilder.field(range, ((Ast.RecordSelector) apply.fn).name);
      }
      if (apply.fn instanceof Ast.Id) {
        final Ast.Tuple tuple = (Ast.Tuple) apply.arg;
        final SqlOperator op = DIRECT_OPS.get(((Ast.Id) apply.fn).name);
        if (op != null) {
          return cx.relBuilder.call(op, translateList(cx, tuple.args()));
        }
      }
      break;

    case ANDALSO:
    case ORELSE:
      final Ast.InfixCall infix = (Ast.InfixCall) exp;
      final SqlOperator op = INFIX_OPERATORS.get(infix.op);
      return cx.relBuilder.call(op, translateList(cx, infix.args()));

    case RECORD:
      record = (Ast.Record) exp;
      final RelDataTypeFactory.Builder builder =
          cx.relBuilder.getTypeFactory().builder();
      final List<RexNode> operands = new ArrayList<>();
      record.args.forEach((name, arg) -> {
        final RexNode e = translate(cx, arg);
        operands.add(e);
        builder.add(name, e.getType());
      });
      final RelDataType type = builder.build();
      return cx.relBuilder.getRexBuilder().makeCall(type,
          SqlStdOperatorTable.ROW, operands);
    }

    throw new AssertionError("cannot translate " + exp.op + " [" + exp + "]");
  }

  private Ast.Record toRecord(RelContext cx, Ast.Id id) {
    final Type type = cx.env.get(id.name).type;
    if (type instanceof RecordType) {
      final SortedMap<String, Ast.Exp> map = new TreeMap<>();
      ((RecordType) type).argNameTypes.keySet().forEach(field ->
          map.put(field,
              ast.apply(ast.recordSelector(id.pos, field), id)));
      return ast.record(id.pos, map);
    }
    return null;
  }

  private List<RexNode> translateList(RelContext cx, List<Ast.Exp> exps) {
    final ImmutableList.Builder<RexNode> list = ImmutableList.builder();
    for (Ast.Exp exp : exps) {
      list.add(translate(cx, exp));
    }
    return list.build();
  }

  private RelContext where(RelContext cx, Ast.Where where) {
    cx.relBuilder.filter(translate(cx, where.exp));
    return cx;
  }

  private RelContext order(RelContext cx, Ast.Order order) {
    final List<RexNode> exps = new ArrayList<>();
    order.orderItems.forEach(i -> {
      RexNode exp = translate(cx, i.exp);
      if (i.direction == Ast.Direction.DESC) {
        exp = cx.relBuilder.desc(exp);
      }
      exps.add(exp);
    });
    cx.relBuilder.sort(exps);
    return cx;
  }

  private RelContext group(RelContext cx, Ast.Group group) {
    final Map<String, Function<RelBuilder, RexNode>> map = new HashMap<>();
    final List<Binding> bindings = new ArrayList<>();
    final List<RexNode> nodes = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    Pair.forEach(group.groupExps, (id, exp) -> {
      bindings.add(Binding.of(id.name, typeMap.getType(id)));
      nodes.add(translate(cx, exp));
      names.add(id.name);
    });
    final RelBuilder.GroupKey groupKey = cx.relBuilder.groupKey(nodes);
    final List<RelBuilder.AggCall> aggregateCalls = new ArrayList<>();
    group.aggregates.forEach(aggregate -> {
      bindings.add(Binding.of(aggregate.id.name, typeMap.getType(aggregate)));
      final SqlAggFunction op = aggOp(aggregate.aggregate);
      final ImmutableList.Builder<RexNode> args = ImmutableList.builder();
      if (aggregate.argument != null) {
        args.add(translate(cx, aggregate.argument));
      }
      aggregateCalls.add(
          cx.relBuilder.aggregateCall(op, args.build())
              .as(aggregate.id.name));
      names.add(aggregate.id.name);
    });

    // Create an Aggregate operator.
    cx.relBuilder.aggregate(groupKey, aggregateCalls);

    // Permute the fields so that they are sorted by name, per Morel records.
    final List<String> sortedNames =
        Ordering.natural().immutableSortedCopy(names);
    cx.relBuilder.project(cx.relBuilder.fields(sortedNames));
    sortedNames.forEach(name -> {
      final int i = map.size();
      map.put(name, b -> b.field(1, 0, i));
    });

    // Return a context containing a variable for each output field.
    return new RelContext(cx.env.bindAll(bindings), cx.relBuilder, map, 1);
  }

  /** Returns the Calcite operator corresponding to a Morel built-in aggregate
   * function.
   *
   * <p>Future work: rather than resolving by name, look up aggregate function
   * in environment, and compare with standard implementation of "sum" etc.;
   * support aggregate functions defined by expressions (e.g. lambdas). */
  @Nonnull private SqlAggFunction aggOp(Ast.Exp aggregate) {
    if (aggregate instanceof Ast.Id) {
      final String name = ((Ast.Id) aggregate).name;
      switch (name) {
      case "sum":
        return SqlStdOperatorTable.SUM;
      case "count":
        return SqlStdOperatorTable.COUNT;
      case "min":
        return SqlStdOperatorTable.MIN;
      case "max":
        return SqlStdOperatorTable.MAX;
      }
    }
    throw new AssertionError("unknown aggregate function: " + aggregate);
  }

  private static EvalEnv evalEnvOf(Environment env) {
    final Map<String, Object> map = new HashMap<>();
    env.forEachValue(map::put);
    EMPTY_ENV.visit(map::putIfAbsent);
    return EvalEnvs.copyOf(map);
  }

  /** Translation context. */
  static class RelContext extends Context {
    final RelBuilder relBuilder;
    final Map<String, Function<RelBuilder, RexNode>> map;
    final int inputCount;

    RelContext(Environment env, RelBuilder relBuilder,
        Map<String, Function<RelBuilder, RexNode>> map, int inputCount) {
      super(env);
      this.relBuilder = relBuilder;
      this.map = map;
      this.inputCount = inputCount;
    }
  }

  /** Extension to {@link Code} that can also provide a translation to
   * relational algebra. */
  interface RelCode extends Code {
    void toRel(RelContext cx);

    static RelCode of(Code code, Consumer<RelContext> c) {
      return new RelCode() {
        @Override public Object eval(EvalEnv env) {
          return code.eval(env);
        }

        @Override public void toRel(RelContext cx) {
          c.accept(cx);
        }
      };
    }
  }

  /** How a Morel variable maps onto the columns returned from a Join. */
  private static class VarData {
    final Type type;
    final int offset;
    final RelDataType rowType;

    VarData(Type type, int offset, RelDataType rowType) {
      this.type = type;
      this.offset = offset;
      this.rowType = rowType;
    }
  }

  /** Converter from Calcite types to Morel types. */
  static class CalciteToMorelConverter {
    final RelDataType calciteType;
    final Type morelType;

    CalciteToMorelConverter(RelDataType calciteType, Type morelType) {
      this.calciteType = calciteType;
      this.morelType = morelType;
    }

    /** Creates a converter for a given Morel type, in the process deducing the
     * corresponding Calcite type. */
    public static CalciteToMorelConverter forMorel(Type type,
        RelDataTypeFactory typeFactory) {
      switch (type.op()) {
      case LIST:
        final ListType listType = (ListType) type;
        return new CalciteToMorelConverter(
            typeFactory.createMultisetType(
                forMorel(listType.elementType, typeFactory).calciteType, -1),
            type);

      case RECORD_TYPE:
        final RelDataTypeFactory.Builder typeBuilder = typeFactory.builder();
        final RecordType recordType = (RecordType) type;
        recordType.argNameTypes.forEach((name, argType) ->
            typeBuilder.add(name, forMorel(argType, typeFactory).calciteType));
        return new CalciteToMorelConverter(typeBuilder.build(), type);

      case ID:
        final PrimitiveType primitiveType = (PrimitiveType) type;
        switch (primitiveType) {
        case BOOL:
          return new CalciteToMorelConverter(
              typeFactory.createSqlType(SqlTypeName.BOOLEAN), type);
        case INT:
          return new CalciteToMorelConverter(
              typeFactory.createSqlType(SqlTypeName.INTEGER), type);
        case REAL:
          return new CalciteToMorelConverter(
              typeFactory.createSqlType(SqlTypeName.REAL), type);
        case CHAR:
          return new CalciteToMorelConverter(
              typeFactory.createSqlType(SqlTypeName.SMALLINT), type);
        case UNIT:
          return new CalciteToMorelConverter(
              typeFactory.createSqlType(SqlTypeName.TINYINT), type);
        case STRING:
          return new CalciteToMorelConverter(
              typeFactory.createSqlType(SqlTypeName.VARCHAR, -1), type);
        default:
          throw new AssertionError("unknown type " + type);
        }
      }
      throw new UnsupportedOperationException("cannot convert type " + type);
    }

    public Enumerable<Object[]> toEnumerable(Object v) {
      @SuppressWarnings("unchecked")
      final Enumerable<Object> enumerable =
          Linq4j.asEnumerable((List<Object>) v);
      switch (morelType.op()) {
      case LIST:
        final ListType listType = (ListType) morelType;
        final CalciteToMorelConverter c =
            new CalciteToMorelConverter(calciteType.getComponentType(),
                listType.elementType);
        return EnumerableDefaults.select(enumerable, c::toArray);
      default:
        throw new AssertionError("cannot convert " + morelType);
      }
    }

    private Object[] toArray(Object o) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) o;
      return list.toArray();
    }
  }

  /** Calcite table-valued user-defined function that evaluates a Morel
   * expression and returns the result as a relation. */
  public static class CalciteMorel {
    public ScannableTable eval0(String ml, String typeJson) {
      final Ast.Exp e;
      try {
        e = new MorelParserImpl(new StringReader(ml)).expression();
      } catch (ParseException pe) {
        throw new RuntimeException(pe);
      }
      final TypeSystem typeSystem = new TypeSystem();
      final Environment env =
          Environments.env(typeSystem, ImmutableMap.of());
      final Ast.ValDecl valDecl = Compiles.toValDecl(e);
      final TypeResolver.Resolved resolved =
          TypeResolver.deduceType(env, valDecl, typeSystem);
      final Ast.ValDecl valDecl2 = (Ast.ValDecl) resolved.node;
      final Ast.Exp e2 = Compiles.toExp(valDecl2);
      Type type = resolved.typeMap.getType(e2);
      final Code code = new Compiler(resolved.typeMap).compile(env, e2);
      return new ScannableTable() {

        @Override public RelDataType getRowType(RelDataTypeFactory factory) {
          try {
            return RelJsonReader.readType(factory, typeJson);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }

        @Override public Enumerable<Object[]> scan(DataContext root) {
          final CalciteToMorelConverter converter =
              CalciteToMorelConverter.forMorel(type, root.getTypeFactory());
          final EvalEnv evalEnv = Codes.emptyEnv();
          Object v = code.eval(evalEnv);
          return converter.toEnumerable(v);
        }

        @Override public Statistic getStatistic() {
          return Statistics.UNKNOWN;
        }

        @Override public Schema.TableType getJdbcTableType() {
          return Schema.TableType.OTHER;
        }

        @Override public boolean isRolledUp(String column) {
          return false;
        }

        @Override public boolean rolledUpColumnValidInsideAgg(String column,
            SqlCall call, SqlNode parent, CalciteConnectionConfig config) {
          return false;
        }
      };
    }
  }

  /** Operand to a user-defined function. */
  private interface Arg {
    String name();
    RelDataType type(RelDataTypeFactory typeFactory);
    SqlTypeFamily family();
    boolean optional();

    @SuppressWarnings({"StaticPseudoFunctionalStyleMethod",
        "ConstantConditions"})
    static SqlOperandMetadata metadata(List<Arg> args) {
      return OperandTypes.operandMetadata(Lists.transform(args, Arg::family),
          typeFactory -> Lists.transform(args, arg -> arg.type(typeFactory)),
          i -> args.get(i).name(), i -> args.get(i).optional());
    }

    static Arg of(String name,
        Function<RelDataTypeFactory, RelDataType> protoType,
        SqlTypeFamily family, boolean optional) {
      return new Arg() {
        @Override public String name() {
          return name;
        }

        @Override public RelDataType type(RelDataTypeFactory typeFactory) {
          return protoType.apply(typeFactory);
        }

        @Override public SqlTypeFamily family() {
          return family;
        }

        @Override public boolean optional() {
          return optional;
        }
      };
    }
  }

  /** Converts Morel types to JSON. */
  private static class MorelRelJson extends RelJson {
    private final JsonBuilder jsonBuilder;

    MorelRelJson(JsonBuilder jsonBuilder) {
      super(jsonBuilder);
      this.jsonBuilder = jsonBuilder;
    }

    @Override public Object toJson(Object value) {
      if (value instanceof Type) {
        return toJson((Type) value);
      }
      return super.toJson(value);
    }

    public Object toJson(Type type) {
      if (type instanceof PrimitiveType) {
        return ((PrimitiveType) type).moniker;
      } else if (type instanceof ListType) {
        final Map<String, Object> map = jsonBuilder.map();
        ListType listType = (ListType) type;
        map.put("list", toJson(listType.elementType));
        return map;
      } else if (type instanceof RecordType) {
        final Map<String, Object> map = jsonBuilder.map();
        RecordType recordType = (RecordType) type;
        final List<Object> list = jsonBuilder.list();
        recordType.argNameTypes.forEach((name, argType) -> {
          final Map<String, Object> map2 = jsonBuilder.map();
          map2.put(name, toJson(argType));
          list.add(map2);
        });
        map.put("record", list);
        return map;
      } else {
        throw new AssertionError("unknown type " + type);
      }
    }
  }
}

// End CalciteCompiler.java
