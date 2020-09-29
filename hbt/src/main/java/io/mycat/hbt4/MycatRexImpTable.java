/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mycat.hbt4;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.apache.calcite.adapter.enumerable.*;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.util.DateTimeUtils;
import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.avatica.util.TimeUnitRange;
import org.apache.calcite.linq4j.tree.*;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeFactoryImpl;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.SqlFunctions;
import org.apache.calcite.schema.ImplementableAggFunction;
import org.apache.calcite.schema.ImplementableFunction;
import org.apache.calcite.schema.impl.AggregateFunctionImpl;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlJsonArrayAggAggFunction;
import org.apache.calcite.sql.fun.SqlJsonObjectAggAggFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.fun.SqlTrimFunction;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlUserDefinedAggFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedTableFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedTableMacro;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.Util;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.apache.calcite.adapter.enumerable.EnumUtils.generateCollatorExpression;
import static org.apache.calcite.linq4j.tree.ExpressionType.*;
import static org.apache.calcite.sql.fun.SqlLibraryOperators.JSON_DEPTH;
import static org.apache.calcite.sql.fun.SqlLibraryOperators.JSON_KEYS;
import static org.apache.calcite.sql.fun.SqlLibraryOperators.JSON_LENGTH;
import static org.apache.calcite.sql.fun.SqlLibraryOperators.JSON_PRETTY;
import static org.apache.calcite.sql.fun.SqlLibraryOperators.JSON_REMOVE;
import static org.apache.calcite.sql.fun.SqlLibraryOperators.JSON_STORAGE_SIZE;
import static org.apache.calcite.sql.fun.SqlLibraryOperators.JSON_TYPE;
import static org.apache.calcite.sql.fun.SqlLibraryOperators.*;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.*;

/**
 * Contains implementations of Rex operators as Java code.
 */
public class MycatRexImpTable {
    public static final MycatRexImpTable INSTANCE = new MycatRexImpTable();

    public static final ConstantExpression NULL_EXPR =
            Expressions.constant(null);
    public static final ConstantExpression FALSE_EXPR =
            Expressions.constant(false);
    public static final ConstantExpression TRUE_EXPR =
            Expressions.constant(true);
    public static final ConstantExpression COMMA_EXPR =
            Expressions.constant(",");
    public static final MemberExpression BOXED_FALSE_EXPR =
            Expressions.field(null, Boolean.class, "FALSE");
    public static final MemberExpression BOXED_TRUE_EXPR =
            Expressions.field(null, Boolean.class, "TRUE");

    private final Map<SqlOperator, RexCallImplementor> map = new HashMap<>();
    private final Map<SqlAggFunction, Supplier<? extends AggImplementor>> aggMap =
            new HashMap<>();
    private final Map<SqlAggFunction, Supplier<? extends WinAggImplementor>> winAggMap =
            new HashMap<>();
    private final Map<SqlMatchFunction, Supplier<? extends MatchImplementor>> matchMap =
            new HashMap<>();
    private final Map<SqlOperator, Supplier<? extends TableFunctionCallImplementor>>
            tvfImplementorMap = new HashMap<>();

    MycatRexImpTable() {
        defineMethod(ROW, BuiltInMethod.ARRAY.method, NullPolicy.NONE);
        defineMethod(UPPER, BuiltInMethod.UPPER.method, NullPolicy.STRICT);
        defineMethod(LOWER, BuiltInMethod.LOWER.method, NullPolicy.STRICT);
        defineMethod(INITCAP, BuiltInMethod.INITCAP.method, NullPolicy.STRICT);
        defineMethod(TO_BASE64, BuiltInMethod.TO_BASE64.method, NullPolicy.STRICT);
        defineMethod(FROM_BASE64, BuiltInMethod.FROM_BASE64.method, NullPolicy.STRICT);
        defineMethod(MD5, BuiltInMethod.MD5.method, NullPolicy.STRICT);
        defineMethod(SHA1, BuiltInMethod.SHA1.method, NullPolicy.STRICT);
        defineMethod(SUBSTRING, BuiltInMethod.SUBSTRING.method, NullPolicy.STRICT);
        defineMethod(LEFT, BuiltInMethod.LEFT.method, NullPolicy.ANY);
        defineMethod(RIGHT, BuiltInMethod.RIGHT.method, NullPolicy.ANY);
        defineMethod(REPLACE, BuiltInMethod.REPLACE.method, NullPolicy.STRICT);
        defineMethod(TRANSLATE3, BuiltInMethod.TRANSLATE3.method, NullPolicy.STRICT);
        defineMethod(CHR, "chr", NullPolicy.STRICT);
        defineMethod(CHARACTER_LENGTH, BuiltInMethod.CHAR_LENGTH.method,
                NullPolicy.STRICT);
        defineMethod(CHAR_LENGTH, BuiltInMethod.CHAR_LENGTH.method,
                NullPolicy.STRICT);
        defineMethod(CONCAT, BuiltInMethod.STRING_CONCAT.method,
                NullPolicy.STRICT);
        defineMethod(CONCAT_FUNCTION, BuiltInMethod.MULTI_STRING_CONCAT.method, NullPolicy.STRICT);
        defineMethod(CONCAT2, BuiltInMethod.STRING_CONCAT.method, NullPolicy.STRICT);
        defineMethod(OVERLAY, BuiltInMethod.OVERLAY.method, NullPolicy.STRICT);
        defineMethod(POSITION, BuiltInMethod.POSITION.method, NullPolicy.STRICT);
        defineMethod(ASCII, BuiltInMethod.ASCII.method, NullPolicy.STRICT);
        defineMethod(REPEAT, BuiltInMethod.REPEAT.method, NullPolicy.STRICT);
        defineMethod(SPACE, BuiltInMethod.SPACE.method, NullPolicy.STRICT);
        defineMethod(STRCMP, BuiltInMethod.STRCMP.method, NullPolicy.STRICT);
        defineMethod(SOUNDEX, BuiltInMethod.SOUNDEX.method, NullPolicy.STRICT);
        defineMethod(DIFFERENCE, BuiltInMethod.DIFFERENCE.method, NullPolicy.STRICT);
        defineMethod(REVERSE, BuiltInMethod.REVERSE.method, NullPolicy.STRICT);

        map.put(TRIM, new TrimImplementor());

        // logical
        map.put(AND, new LogicalAndImplementor());
        map.put(OR, new LogicalOrImplementor());
        map.put(NOT, new LogicalNotImplementor());

        // comparisons
        defineBinary(LESS_THAN, LessThan, NullPolicy.STRICT, "lt");
        defineBinary(LESS_THAN_OR_EQUAL, LessThanOrEqual, NullPolicy.STRICT, "le");
        defineBinary(GREATER_THAN, GreaterThan, NullPolicy.STRICT, "gt");
        defineBinary(GREATER_THAN_OR_EQUAL, GreaterThanOrEqual, NullPolicy.STRICT,
                "ge");
        defineBinary(EQUALS, Equal, NullPolicy.STRICT, "eq");
        defineBinary(NOT_EQUALS, NotEqual, NullPolicy.STRICT, "ne");

        // arithmetic
        defineBinary(PLUS, Add, NullPolicy.STRICT, "plus");
        defineBinary(MINUS, Subtract, NullPolicy.STRICT, "minus");
        defineBinary(MULTIPLY, Multiply, NullPolicy.STRICT, "multiply");
        defineBinary(DIVIDE, Divide, NullPolicy.STRICT, "divide");
        defineBinary(DIVIDE_INTEGER, Divide, NullPolicy.STRICT, "divide");
        defineUnary(UNARY_MINUS, Negate, NullPolicy.STRICT);
        defineUnary(UNARY_PLUS, UnaryPlus, NullPolicy.STRICT);

        defineMethod(MOD, "mod", NullPolicy.STRICT);
        defineMethod(EXP, "exp", NullPolicy.STRICT);
        defineMethod(POWER, "power", NullPolicy.STRICT);
        defineMethod(LN, "ln", NullPolicy.STRICT);
        defineMethod(LOG10, "log10", NullPolicy.STRICT);
        defineMethod(ABS, "abs", NullPolicy.STRICT);

        map.put(RAND, new RandImplementor());
        map.put(RAND_INTEGER, new RandIntegerImplementor());

        defineMethod(ACOS, "acos", NullPolicy.STRICT);
        defineMethod(ASIN, "asin", NullPolicy.STRICT);
        defineMethod(ATAN, "atan", NullPolicy.STRICT);
        defineMethod(ATAN2, "atan2", NullPolicy.STRICT);
        defineMethod(CBRT, "cbrt", NullPolicy.STRICT);
        defineMethod(COS, "cos", NullPolicy.STRICT);
        defineMethod(COSH, "cosh", NullPolicy.STRICT);
        defineMethod(COT, "cot", NullPolicy.STRICT);
        defineMethod(DEGREES, "degrees", NullPolicy.STRICT);
        defineMethod(RADIANS, "radians", NullPolicy.STRICT);
        defineMethod(ROUND, "sround", NullPolicy.STRICT);
        defineMethod(SIGN, "sign", NullPolicy.STRICT);
        defineMethod(SIN, "sin", NullPolicy.STRICT);
        defineMethod(SINH, "sinh", NullPolicy.STRICT);
        defineMethod(TAN, "tan", NullPolicy.STRICT);
        defineMethod(TANH, "tanh", NullPolicy.STRICT);
        defineMethod(TRUNCATE, "struncate", NullPolicy.STRICT);

        map.put(PI, new PiImplementor());

        // datetime
        map.put(DATETIME_PLUS, new DatetimeArithmeticImplementor());
        map.put(MINUS_DATE, new DatetimeArithmeticImplementor());
        map.put(EXTRACT, new ExtractImplementor());
        map.put(FLOOR,
                new FloorImplementor(BuiltInMethod.FLOOR.method.getName(),
                        BuiltInMethod.UNIX_TIMESTAMP_FLOOR.method,
                        BuiltInMethod.UNIX_DATE_FLOOR.method));
        map.put(CEIL,
                new FloorImplementor(BuiltInMethod.CEIL.method.getName(),
                        BuiltInMethod.UNIX_TIMESTAMP_CEIL.method,
                        BuiltInMethod.UNIX_DATE_CEIL.method));

        defineMethod(LAST_DAY, "lastDay", NullPolicy.STRICT);
        map.put(DAYNAME,
                new PeriodNameImplementor("dayName",
                        BuiltInMethod.DAYNAME_WITH_TIMESTAMP,
                        BuiltInMethod.DAYNAME_WITH_DATE));
        map.put(MONTHNAME,
                new PeriodNameImplementor("monthName",
                        BuiltInMethod.MONTHNAME_WITH_TIMESTAMP,
                        BuiltInMethod.MONTHNAME_WITH_DATE));
        defineMethod(TIMESTAMP_SECONDS, "timestampSeconds", NullPolicy.STRICT);
        defineMethod(TIMESTAMP_MILLIS, "timestampMillis", NullPolicy.STRICT);
        defineMethod(TIMESTAMP_MICROS, "timestampMicros", NullPolicy.STRICT);
        defineMethod(UNIX_SECONDS, "unixSeconds", NullPolicy.STRICT);
        defineMethod(UNIX_MILLIS, "unixMillis", NullPolicy.STRICT);
        defineMethod(UNIX_MICROS, "unixMicros", NullPolicy.STRICT);
        defineMethod(DATE_FROM_UNIX_DATE, "dateFromUnixDate", NullPolicy.STRICT);
        defineMethod(UNIX_DATE, "unixDate", NullPolicy.STRICT);

        map.put(IS_NULL, new IsNullImplementor());
        map.put(IS_NOT_NULL, new IsNotNullImplementor());
        map.put(IS_TRUE, new IsTrueImplementor());
        map.put(IS_NOT_TRUE, new IsNotTrueImplementor());
        map.put(IS_FALSE, new IsFalseImplementor());
        map.put(IS_NOT_FALSE, new IsNotFalseImplementor());

        // LIKE and SIMILAR
        final MethodImplementor likeImplementor =
                new MethodImplementor(BuiltInMethod.LIKE.method, NullPolicy.STRICT,
                        false);
        map.put(LIKE, likeImplementor);
        map.put(NOT_LIKE, likeImplementor);
        final MethodImplementor similarImplementor =
                new MethodImplementor(BuiltInMethod.SIMILAR.method, NullPolicy.STRICT,
                        false);
        map.put(SIMILAR_TO, similarImplementor);
        map.put(NOT_SIMILAR_TO, NotImplementor.of(similarImplementor));

        // POSIX REGEX
        final MethodImplementor posixRegexImplementor =
                new MethodImplementor(BuiltInMethod.POSIX_REGEX.method,
                        NullPolicy.STRICT, false);
        map.put(SqlStdOperatorTable.POSIX_REGEX_CASE_INSENSITIVE,
                posixRegexImplementor);
        map.put(SqlStdOperatorTable.POSIX_REGEX_CASE_SENSITIVE,
                posixRegexImplementor);
        map.put(SqlStdOperatorTable.NEGATED_POSIX_REGEX_CASE_INSENSITIVE,
                NotImplementor.of(posixRegexImplementor));
        map.put(SqlStdOperatorTable.NEGATED_POSIX_REGEX_CASE_SENSITIVE,
                NotImplementor.of(posixRegexImplementor));
        map.put(REGEXP_REPLACE, new RegexpReplaceImplementor());

        // Multisets & arrays
        defineMethod(CARDINALITY, BuiltInMethod.COLLECTION_SIZE.method,
                NullPolicy.STRICT);
        defineMethod(SLICE, BuiltInMethod.SLICE.method, NullPolicy.NONE);
        defineMethod(ELEMENT, BuiltInMethod.ELEMENT.method, NullPolicy.STRICT);
        defineMethod(STRUCT_ACCESS, BuiltInMethod.STRUCT_ACCESS.method, NullPolicy.ANY);
        defineMethod(MEMBER_OF, BuiltInMethod.MEMBER_OF.method, NullPolicy.NONE);
        final MethodImplementor isEmptyImplementor =
                new MethodImplementor(BuiltInMethod.IS_EMPTY.method, NullPolicy.NONE,
                        false);
        map.put(IS_EMPTY, isEmptyImplementor);
        map.put(IS_NOT_EMPTY, NotImplementor.of(isEmptyImplementor));
        final MethodImplementor isASetImplementor =
                new MethodImplementor(BuiltInMethod.IS_A_SET.method, NullPolicy.NONE,
                        false);
        map.put(IS_A_SET, isASetImplementor);
        map.put(IS_NOT_A_SET, NotImplementor.of(isASetImplementor));
        defineMethod(MULTISET_INTERSECT_DISTINCT,
                BuiltInMethod.MULTISET_INTERSECT_DISTINCT.method, NullPolicy.NONE);
        defineMethod(MULTISET_INTERSECT,
                BuiltInMethod.MULTISET_INTERSECT_ALL.method, NullPolicy.NONE);
        defineMethod(MULTISET_EXCEPT_DISTINCT,
                BuiltInMethod.MULTISET_EXCEPT_DISTINCT.method, NullPolicy.NONE);
        defineMethod(MULTISET_EXCEPT, BuiltInMethod.MULTISET_EXCEPT_ALL.method, NullPolicy.NONE);
        defineMethod(MULTISET_UNION_DISTINCT,
                BuiltInMethod.MULTISET_UNION_DISTINCT.method, NullPolicy.NONE);
        defineMethod(MULTISET_UNION, BuiltInMethod.MULTISET_UNION_ALL.method, NullPolicy.NONE);
        final MethodImplementor subMultisetImplementor =
                new MethodImplementor(BuiltInMethod.SUBMULTISET_OF.method, NullPolicy.NONE, false);
        map.put(SUBMULTISET_OF, subMultisetImplementor);
        map.put(NOT_SUBMULTISET_OF, NotImplementor.of(subMultisetImplementor));

        map.put(COALESCE, new CoalesceImplementor());
        map.put(CAST, new CastImplementor());
        map.put(DATE, new CastImplementor());

        map.put(REINTERPRET, new ReinterpretImplementor());

        final RexCallImplementor value = new ValueConstructorImplementor();
        map.put(MAP_VALUE_CONSTRUCTOR, value);
        map.put(ARRAY_VALUE_CONSTRUCTOR, value);
        map.put(ITEM, new ItemImplementor());

        map.put(DEFAULT, new DefaultImplementor());

        // Sequences
        defineMethod(CURRENT_VALUE, BuiltInMethod.SEQUENCE_CURRENT_VALUE.method,
                NullPolicy.STRICT);
        defineMethod(NEXT_VALUE, BuiltInMethod.SEQUENCE_NEXT_VALUE.method,
                NullPolicy.STRICT);

        // Compression Operators
        defineMethod(COMPRESS, BuiltInMethod.COMPRESS.method, NullPolicy.ARG0);

        // Xml Operators
        defineMethod(EXTRACT_VALUE, BuiltInMethod.EXTRACT_VALUE.method, NullPolicy.ARG0);
        defineMethod(XML_TRANSFORM, BuiltInMethod.XML_TRANSFORM.method, NullPolicy.ARG0);
        defineMethod(EXTRACT_XML, BuiltInMethod.EXTRACT_XML.method, NullPolicy.ARG0);
        defineMethod(EXISTS_NODE, BuiltInMethod.EXISTS_NODE.method, NullPolicy.ARG0);

        // Json Operators
        defineMethod(JSON_VALUE_EXPRESSION,
                BuiltInMethod.JSON_VALUE_EXPRESSION.method, NullPolicy.STRICT);
        defineMethod(JSON_EXISTS, BuiltInMethod.JSON_EXISTS.method, NullPolicy.ARG0);
        map.put(JSON_VALUE,
                new JsonValueImplementor(BuiltInMethod.JSON_VALUE.method));
        defineMethod(JSON_QUERY, BuiltInMethod.JSON_QUERY.method, NullPolicy.ARG0);
        defineMethod(JSON_TYPE, BuiltInMethod.JSON_TYPE.method, NullPolicy.ARG0);
        defineMethod(JSON_DEPTH, BuiltInMethod.JSON_DEPTH.method, NullPolicy.ARG0);
        defineMethod(JSON_KEYS, BuiltInMethod.JSON_KEYS.method, NullPolicy.ARG0);
        defineMethod(JSON_PRETTY, BuiltInMethod.JSON_PRETTY.method, NullPolicy.ARG0);
        defineMethod(JSON_LENGTH, BuiltInMethod.JSON_LENGTH.method, NullPolicy.ARG0);
        defineMethod(JSON_REMOVE, BuiltInMethod.JSON_REMOVE.method, NullPolicy.ARG0);
        defineMethod(JSON_STORAGE_SIZE, BuiltInMethod.JSON_STORAGE_SIZE.method, NullPolicy.ARG0);
        defineMethod(JSON_OBJECT, BuiltInMethod.JSON_OBJECT.method, NullPolicy.NONE);
        defineMethod(JSON_ARRAY, BuiltInMethod.JSON_ARRAY.method, NullPolicy.NONE);
        aggMap.put(JSON_OBJECTAGG.with(SqlJsonConstructorNullClause.ABSENT_ON_NULL),
                JsonObjectAggImplementor
                        .supplierFor(BuiltInMethod.JSON_OBJECTAGG_ADD.method));
        aggMap.put(JSON_OBJECTAGG.with(SqlJsonConstructorNullClause.NULL_ON_NULL),
                JsonObjectAggImplementor
                        .supplierFor(BuiltInMethod.JSON_OBJECTAGG_ADD.method));
        aggMap.put(JSON_ARRAYAGG.with(SqlJsonConstructorNullClause.ABSENT_ON_NULL),
                JsonArrayAggImplementor
                        .supplierFor(BuiltInMethod.JSON_ARRAYAGG_ADD.method));
        aggMap.put(JSON_ARRAYAGG.with(SqlJsonConstructorNullClause.NULL_ON_NULL),
                JsonArrayAggImplementor
                        .supplierFor(BuiltInMethod.JSON_ARRAYAGG_ADD.method));
        map.put(IS_JSON_VALUE,
                new MethodImplementor(BuiltInMethod.IS_JSON_VALUE.method,
                        NullPolicy.NONE, false));
        map.put(IS_JSON_OBJECT,
                new MethodImplementor(BuiltInMethod.IS_JSON_OBJECT.method,
                        NullPolicy.NONE, false));
        map.put(IS_JSON_ARRAY,
                new MethodImplementor(BuiltInMethod.IS_JSON_ARRAY.method,
                        NullPolicy.NONE, false));
        map.put(IS_JSON_SCALAR,
                new MethodImplementor(BuiltInMethod.IS_JSON_SCALAR.method,
                        NullPolicy.NONE, false));
        map.put(IS_NOT_JSON_VALUE,
                NotImplementor.of(
                        new MethodImplementor(BuiltInMethod.IS_JSON_VALUE.method,
                                NullPolicy.NONE, false)));
        map.put(IS_NOT_JSON_OBJECT,
                NotImplementor.of(
                        new MethodImplementor(BuiltInMethod.IS_JSON_OBJECT.method,
                                NullPolicy.NONE, false)));
        map.put(IS_NOT_JSON_ARRAY,
                NotImplementor.of(
                        new MethodImplementor(BuiltInMethod.IS_JSON_ARRAY.method,
                                NullPolicy.NONE, false)));
        map.put(IS_NOT_JSON_SCALAR,
                NotImplementor.of(
                        new MethodImplementor(BuiltInMethod.IS_JSON_SCALAR.method,
                                NullPolicy.NONE, false)));

        // System functions
        final SystemFunctionImplementor systemFunctionImplementor =
                new SystemFunctionImplementor();
        map.put(USER, systemFunctionImplementor);
        map.put(CURRENT_USER, systemFunctionImplementor);
        map.put(SESSION_USER, systemFunctionImplementor);
        map.put(SYSTEM_USER, systemFunctionImplementor);
        map.put(CURRENT_PATH, systemFunctionImplementor);
        map.put(CURRENT_ROLE, systemFunctionImplementor);
        map.put(CURRENT_CATALOG, systemFunctionImplementor);

        // Current time functions
        map.put(CURRENT_TIME, systemFunctionImplementor);
        map.put(CURRENT_TIMESTAMP, systemFunctionImplementor);
        map.put(CURRENT_DATE, systemFunctionImplementor);
        map.put(LOCALTIME, systemFunctionImplementor);
        map.put(LOCALTIMESTAMP, systemFunctionImplementor);

        aggMap.put(COUNT, constructorSupplier(CountImplementor.class));
        aggMap.put(REGR_COUNT, constructorSupplier(CountImplementor.class));
        aggMap.put(SUM0, constructorSupplier(SumImplementor.class));
        aggMap.put(SUM, constructorSupplier(SumImplementor.class));
        Supplier<MinMaxImplementor> minMax =
                constructorSupplier(MinMaxImplementor.class);
        aggMap.put(MIN, minMax);
        aggMap.put(MAX, minMax);
        aggMap.put(ANY_VALUE, minMax);
        aggMap.put(SOME, minMax);
        aggMap.put(EVERY, minMax);
        final Supplier<BitOpImplementor> bitop =
                constructorSupplier(BitOpImplementor.class);
        aggMap.put(BIT_AND, bitop);
        aggMap.put(BIT_OR, bitop);
        aggMap.put(BIT_XOR, bitop);
        aggMap.put(SINGLE_VALUE, constructorSupplier(SingleValueImplementor.class));
        aggMap.put(COLLECT, constructorSupplier(CollectImplementor.class));
        aggMap.put(LISTAGG, constructorSupplier(ListaggImplementor.class));
        aggMap.put(FUSION, constructorSupplier(FusionImplementor.class));
        aggMap.put(INTERSECTION, constructorSupplier(IntersectionImplementor.class));
        final Supplier<GroupingImplementor> grouping =
                constructorSupplier(GroupingImplementor.class);
        aggMap.put(GROUPING, grouping);
        aggMap.put(GROUPING_ID, grouping);

    }

    private <T> Supplier<T> constructorSupplier(Class<T> klass) {
        final Constructor<T> constructor;
        try {
            constructor = klass.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    klass + " should implement zero arguments constructor");
        }
        return () -> {
            try {
                return constructor.newInstance();
            } catch (InstantiationException | IllegalAccessException
                    | InvocationTargetException e) {
                throw new IllegalStateException(
                        "Error while creating aggregate implementor " + constructor, e);
            }
        };
    }


    private void defineMethod(SqlOperator operator, String functionName,
                              NullPolicy nullPolicy) {
        map.put(operator,
                new MethodNameImplementor(functionName, nullPolicy, false));
    }

    private void defineMethod(SqlOperator operator, Method method,
                              NullPolicy nullPolicy) {
        map.put(operator, new MethodImplementor(method, nullPolicy, false));
    }

    private void defineUnary(SqlOperator operator, ExpressionType expressionType,
                             NullPolicy nullPolicy) {
        map.put(operator, new UnaryImplementor(expressionType, nullPolicy));
    }

    private void defineBinary(SqlOperator operator, ExpressionType expressionType,
                              NullPolicy nullPolicy, String backupMethodName) {
        map.put(operator,
                new BinaryImplementor(nullPolicy, true, expressionType,
                        backupMethodName));
    }


    public RexCallImplementor get(final SqlOperator operator) {
        if (operator instanceof SqlUserDefinedFunction) {
            org.apache.calcite.schema.Function udf =
                    ((SqlUserDefinedFunction) operator).getFunction();
            if (!(udf instanceof ImplementableFunction)) {
                throw new IllegalStateException("User defined function " + operator
                        + " must implement ImplementableFunction");
            }
          ImplementableFunction implementableFunction = (ImplementableFunction) udf;
          return null;
        } else if (operator instanceof SqlTypeConstructorFunction) {
            return map.get(SqlStdOperatorTable.ROW);
        }
        return map.get(operator);
    }

    private static MycatRexImpTable.RexCallImplementor createRexCallImplementor(
            final MycatNotNullImplementor implementor,
            final NullPolicy nullPolicy,
            final boolean harmonize) {
        return new MycatRexImpTable.AbstractRexCallImplementor(nullPolicy, harmonize) {
            @Override
            String getVariableName() {
                return "not_null_udf";
            }

            @Override
            Expression implementSafe(MycatRexToLixTranslator translator,
                                     RexCall call, List<Expression> argValueList) {
                return implementor.implement(translator, call, argValueList);
            }
        };
    }

    private static MycatRexImpTable.RexCallImplementor wrapAsRexCallImplementor(
            final MycatCallImplementor implementor) {
        return new MycatRexImpTable.AbstractRexCallImplementor(NullPolicy.NONE, false) {
            @Override
            String getVariableName() {
                return "udf";
            }

            @Override
            Expression implementSafe(MycatRexToLixTranslator translator, RexCall call, List<Expression> argValueList) {
                return implementor.implement(translator, call, MycatRexImpTable.NullAs.NULL);
            }

        };
    }

    public AggImplementor get(final SqlAggFunction aggregation,
                              boolean forWindowAggregate) {
        if (aggregation instanceof SqlUserDefinedAggFunction) {
            final SqlUserDefinedAggFunction udaf =
                    (SqlUserDefinedAggFunction) aggregation;
            if (!(udaf.function instanceof ImplementableAggFunction)) {
                throw new IllegalStateException("User defined aggregation "
                        + aggregation + " must implement ImplementableAggFunction");
            }
            return ((ImplementableAggFunction) udaf.function)
                    .getImplementor(forWindowAggregate);
        }
        if (forWindowAggregate) {
            Supplier<? extends WinAggImplementor> winAgg =
                    winAggMap.get(aggregation);
            if (winAgg != null) {
                return winAgg.get();
            }
            // Regular aggregates can be used in window context as well
        }

        Supplier<? extends AggImplementor> aggSupplier = aggMap.get(aggregation);
        if (aggSupplier == null) {
            return null;
        }

        return aggSupplier.get();
    }

    public MatchImplementor get(final SqlMatchFunction function) {
        final Supplier<? extends MatchImplementor> supplier =
                matchMap.get(function);
        if (supplier != null) {
            return supplier.get();
        } else {
            throw new IllegalStateException("Supplier should not be null");
        }
    }

    public TableFunctionCallImplementor get(final SqlWindowTableFunction operator) {
        final Supplier<? extends TableFunctionCallImplementor> supplier =
                tvfImplementorMap.get(operator);
        if (supplier != null) {
            return supplier.get();
        } else {
            throw new IllegalStateException("Supplier should not be null");
        }
    }

    static Expression optimize(Expression expression) {
        return expression.accept(new OptimizeShuttle());
    }

    static Expression optimize2(Expression operand, Expression expression) {
        if (Primitive.is(operand.getType())) {
            // Primitive values cannot be null
            return optimize(expression);
        } else {
            return optimize(
                    Expressions.condition(
                            Expressions.equal(operand, NULL_EXPR),
                            NULL_EXPR,
                            expression));
        }
    }

    private static RelDataType toSql(RelDataTypeFactory typeFactory,
                                     RelDataType type) {
        if (type instanceof RelDataTypeFactoryImpl.JavaType) {
            final SqlTypeName typeName = type.getSqlTypeName();
            if (typeName != null && typeName != SqlTypeName.OTHER) {
                return typeFactory.createTypeWithNullability(
                        typeFactory.createSqlType(typeName),
                        type.isNullable());
            }
        }
        return type;
    }

    private static <E> boolean allSame(List<E> list) {
        E prev = null;
        for (E e : list) {
            if (prev != null && !prev.equals(e)) {
                return false;
            }
            prev = e;
        }
        return true;
    }

    /**
     * Strategy what an operator should return if one of its
     * arguments is null.
     */
    public enum NullAs {
        /**
         * The most common policy among the SQL built-in operators. If
         * one of the arguments is null, returns null.
         */
        NULL,

        /**
         * If one of the arguments is null, the function returns
         * false. Example: {@code IS NOT NULL}.
         */
        FALSE,

        /**
         * If one of the arguments is null, the function returns
         * true. Example: {@code IS NULL}.
         */
        TRUE,

        /**
         * It is not possible for any of the arguments to be null.  If
         * the argument type is nullable, the enclosing code will already
         * have performed a not-null check. This may allow the operator
         * implementor to generate a more efficient implementation, for
         * example, by avoiding boxing or unboxing.
         */
        NOT_POSSIBLE,

        /**
         * Return false if result is not null, true if result is null.
         */
        IS_NULL,

        /**
         * Return true if result is not null, false if result is null.
         */
        IS_NOT_NULL;

        public static NullAs of(boolean nullable) {
            return nullable ? NULL : NOT_POSSIBLE;
        }

        /**
         * Adapts an expression with "normal" result to one that adheres to
         * this particular policy.
         */
        public Expression handle(Expression x) {
            switch (Primitive.flavor(x.getType())) {
                case PRIMITIVE:
                    // Expression cannot be null. We can skip any runtime checks.
                    switch (this) {
                        case NULL:
                        case NOT_POSSIBLE:
                        case FALSE:
                        case TRUE:
                            return x;
                        case IS_NULL:
                            return FALSE_EXPR;
                        case IS_NOT_NULL:
                            return TRUE_EXPR;
                        default:
                            throw new AssertionError();
                    }
                case BOX:
                    switch (this) {
                        case NOT_POSSIBLE:
                            return EnumUtils.convert(x,
                                    Primitive.ofBox(x.getType()).primitiveClass);
                    }
                    // fall through
            }
            switch (this) {
                case NULL:
                case NOT_POSSIBLE:
                    return x;
                case FALSE:
                    return Expressions.call(BuiltInMethod.IS_TRUE.method, x);
                case TRUE:
                    return Expressions.call(BuiltInMethod.IS_NOT_FALSE.method, x);
                case IS_NULL:
                    return Expressions.equal(x, NULL_EXPR);
                case IS_NOT_NULL:
                    return Expressions.notEqual(x, NULL_EXPR);
                default:
                    throw new AssertionError();
            }
        }
    }

    static Expression getDefaultValue(Type type) {
        if (Primitive.is(type)) {
            Primitive p = Primitive.of(type);
            return Expressions.constant(p.defaultValue, type);
        }
        return Expressions.constant(null, type);
    }

    /**
     * Multiplies an expression by a constant and divides by another constant,
     * optimizing appropriately.
     *
     * <p>For example, {@code multiplyDivide(e, 10, 1000)} returns
     * {@code e / 100}.
     */
    public static Expression multiplyDivide(Expression e, BigDecimal multiplier,
                                            BigDecimal divider) {
        if (multiplier.equals(BigDecimal.ONE)) {
            if (divider.equals(BigDecimal.ONE)) {
                return e;
            }
            return Expressions.divide(e,
                    Expressions.constant(divider.intValueExact()));
        }
        final BigDecimal x =
                multiplier.divide(divider, RoundingMode.UNNECESSARY);
        switch (x.compareTo(BigDecimal.ONE)) {
            case 0:
                return e;
            case 1:
                return Expressions.multiply(e, Expressions.constant(x.intValueExact()));
            case -1:
                return multiplyDivide(e, BigDecimal.ONE, x);
            default:
                throw new AssertionError();
        }
    }

    /**
     * Implementor for the {@code COUNT} aggregate function.
     */
    static class CountImplementor extends StrictAggImplementor {
        @Override
        public void implementNotNullAdd(AggContext info,
                                        AggAddContext add) {
            add.currentBlock().add(
                    Expressions.statement(
                            Expressions.postIncrementAssign(add.accumulator().get(0))));
        }
    }

    /**
     * Implementor for the {@code COUNT} windowed aggregate function.
     */
    static class CountWinImplementor extends StrictWinAggImplementor {
        boolean justFrameRowCount;

        @Override
        public List<Type> getNotNullState(WinAggContext info) {
            boolean hasNullable = false;
            for (RelDataType type : info.parameterRelTypes()) {
                if (type.isNullable()) {
                    hasNullable = true;
                    break;
                }
            }
            if (!hasNullable) {
                justFrameRowCount = true;
                return Collections.emptyList();
            }
            return super.getNotNullState(info);
        }

        @Override
        public void implementNotNullAdd(WinAggContext info,
                                        WinAggAddContext add) {
            if (justFrameRowCount) {
                return;
            }
            add.currentBlock().add(
                    Expressions.statement(
                            Expressions.postIncrementAssign(add.accumulator().get(0))));
        }

        @Override
        protected Expression implementNotNullResult(WinAggContext info,
                                                    WinAggResultContext result) {
            if (justFrameRowCount) {
                return result.getFrameRowCount();
            }
            return super.implementNotNullResult(info, result);
        }
    }

    /**
     * Implementor for the {@code SUM} windowed aggregate function.
     */
    static class SumImplementor extends StrictAggImplementor {
        @Override
        protected void implementNotNullReset(AggContext info,
                                             AggResetContext reset) {
            Expression start = info.returnType() == BigDecimal.class
                    ? Expressions.constant(BigDecimal.ZERO)
                    : Expressions.constant(0);

            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(reset.accumulator().get(0), start)));
        }

        @Override
        public void implementNotNullAdd(AggContext info,
                                        AggAddContext add) {
            Expression acc = add.accumulator().get(0);
            Expression next;
            if (info.returnType() == BigDecimal.class) {
                next = Expressions.call(acc, "add", add.arguments().get(0));
            } else {
                next = Expressions.add(acc,
                        EnumUtils.convert(add.arguments().get(0), acc.type));
            }
            accAdvance(add, acc, next);
        }

        @Override
        public Expression implementNotNullResult(AggContext info,
                                                 AggResultContext result) {
            return super.implementNotNullResult(info, result);
        }
    }

    /**
     * Implementor for the {@code MIN} and {@code MAX} aggregate functions.
     */
    static class MinMaxImplementor extends StrictAggImplementor {
        @Override
        protected void implementNotNullReset(AggContext info,
                                             AggResetContext reset) {
            Expression acc = reset.accumulator().get(0);
            Primitive p = Primitive.of(acc.getType());
            boolean isMin = MIN == info.aggregation();
            Object inf = p == null ? null : (isMin ? p.max : p.min);
            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(acc,
                                    Expressions.constant(inf, acc.getType()))));
        }

        @Override
        public void implementNotNullAdd(AggContext info,
                                        AggAddContext add) {
            Expression acc = add.accumulator().get(0);
            Expression arg = add.arguments().get(0);
            SqlAggFunction aggregation = info.aggregation();
            final Method method = (aggregation == MIN
                    ? BuiltInMethod.LESSER
                    : BuiltInMethod.GREATER).method;
            Expression next = Expressions.call(
                    method.getDeclaringClass(),
                    method.getName(),
                    acc,
                    Expressions.unbox(arg));
            accAdvance(add, acc, next);
        }
    }

    /**
     * Implementor for the {@code SINGLE_VALUE} aggregate function.
     */
    static class SingleValueImplementor implements AggImplementor {
        public List<Type> getStateType(AggContext info) {
            return Arrays.asList(boolean.class, info.returnType());
        }

        public void implementReset(AggContext info, AggResetContext reset) {
            List<Expression> acc = reset.accumulator();
            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(acc.get(0), Expressions.constant(false))));
            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(acc.get(1),
                                    getDefaultValue(acc.get(1).getType()))));
        }

        public void implementAdd(AggContext info, AggAddContext add) {
            List<Expression> acc = add.accumulator();
            Expression flag = acc.get(0);
            add.currentBlock().add(
                    Expressions.ifThen(flag,
                            Expressions.throw_(
                                    Expressions.new_(IllegalStateException.class,
                                            Expressions.constant("more than one value in agg "
                                                    + info.aggregation())))));
            add.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(flag, Expressions.constant(true))));
            add.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(acc.get(1), add.arguments().get(0))));
        }

        public Expression implementResult(AggContext info,
                                          AggResultContext result) {
            return EnumUtils.convert(result.accumulator().get(1),
                    info.returnType());
        }
    }

    /**
     * Implementor for the {@code COLLECT} aggregate function.
     */
    static class CollectImplementor extends StrictAggImplementor {
        @Override
        protected void implementNotNullReset(AggContext info,
                                             AggResetContext reset) {
            // acc[0] = new ArrayList();
            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(reset.accumulator().get(0),
                                    Expressions.new_(ArrayList.class))));
        }

        @Override
        public void implementNotNullAdd(AggContext info,
                                        AggAddContext add) {
            // acc[0].add(arg);
            add.currentBlock().add(
                    Expressions.statement(
                            Expressions.call(add.accumulator().get(0),
                                    BuiltInMethod.COLLECTION_ADD.method,
                                    add.arguments().get(0))));
        }
    }

    /**
     * Implementor for the {@code LISTAGG} aggregate function.
     */
    static class ListaggImplementor extends StrictAggImplementor {
        @Override
        protected void implementNotNullReset(AggContext info,
                                             AggResetContext reset) {
            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(reset.accumulator().get(0), NULL_EXPR)));
        }

        @Override
        public void implementNotNullAdd(AggContext info,
                                        AggAddContext add) {
            final Expression accValue = add.accumulator().get(0);
            final Expression arg0 = add.arguments().get(0);
            final Expression arg1 = add.arguments().size() == 2
                    ? add.arguments().get(1) : COMMA_EXPR;
            final Expression result = Expressions.condition(
                    Expressions.equal(NULL_EXPR, accValue),
                    arg0,
                    Expressions.call(BuiltInMethod.STRING_CONCAT.method, accValue,
                            Expressions.call(BuiltInMethod.STRING_CONCAT.method, arg1, arg0)));

            add.currentBlock().add(Expressions.statement(Expressions.assign(accValue, result)));
        }
    }

    /**
     * Implementor for the {@code INTERSECTION} aggregate function.
     */
    static class IntersectionImplementor extends StrictAggImplementor {
        @Override
        protected void implementNotNullReset(AggContext info, AggResetContext reset) {
            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(reset.accumulator().get(0), Expressions.constant(null))));
        }

        @Override
        public void implementNotNullAdd(AggContext info, AggAddContext add) {
            BlockBuilder accumulatorIsNull = new BlockBuilder();
            accumulatorIsNull.add(
                    Expressions.statement(
                            Expressions.assign(add.accumulator().get(0), Expressions.new_(ArrayList.class))));
            accumulatorIsNull.add(
                    Expressions.statement(
                            Expressions.call(add.accumulator().get(0),
                                    BuiltInMethod.COLLECTION_ADDALL.method, add.arguments().get(0))));

            BlockBuilder accumulatorNotNull = new BlockBuilder();
            accumulatorNotNull.add(
                    Expressions.statement(
                            Expressions.call(add.accumulator().get(0),
                                    BuiltInMethod.COLLECTION_RETAIN_ALL.method,
                                    add.arguments().get(0))
                    )
            );

            add.currentBlock().add(
                    Expressions.ifThenElse(
                            Expressions.equal(add.accumulator().get(0), Expressions.constant(null)),
                            accumulatorIsNull.toBlock(),
                            accumulatorNotNull.toBlock()));
        }
    }

    /**
     * Implementor for the {@code FUSION} aggregate function.
     */
    static class FusionImplementor extends StrictAggImplementor {
        @Override
        protected void implementNotNullReset(AggContext info,
                                             AggResetContext reset) {
            // acc[0] = new ArrayList();
            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(reset.accumulator().get(0),
                                    Expressions.new_(ArrayList.class))));
        }

        @Override
        public void implementNotNullAdd(AggContext info,
                                        AggAddContext add) {
            // acc[0].add(arg);
            add.currentBlock().add(
                    Expressions.statement(
                            Expressions.call(add.accumulator().get(0),
                                    BuiltInMethod.COLLECTION_ADDALL.method,
                                    add.arguments().get(0))));
        }
    }

    /**
     * Implementor for the {@code BIT_AND}, {@code BIT_OR} and {@code BIT_XOR} aggregate function.
     */
    static class BitOpImplementor extends StrictAggImplementor {
        @Override
        protected void implementNotNullReset(AggContext info,
                                             AggResetContext reset) {
            Object initValue = info.aggregation() == BIT_AND ? -1 : 0;
            Expression start = Expressions.constant(initValue, info.returnType());

            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(reset.accumulator().get(0), start)));
        }

        @Override
        public void implementNotNullAdd(AggContext info,
                                        AggAddContext add) {
            Expression acc = add.accumulator().get(0);
            Expression arg = add.arguments().get(0);
            SqlAggFunction aggregation = info.aggregation();

            final BuiltInMethod builtInMethod;
            switch (aggregation.kind) {
                case BIT_AND:
                    builtInMethod = BuiltInMethod.BIT_AND;
                    break;
                case BIT_OR:
                    builtInMethod = BuiltInMethod.BIT_OR;
                    break;
                case BIT_XOR:
                    builtInMethod = BuiltInMethod.BIT_XOR;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown " + aggregation.getName()
                            + ". Only support bit_and, bit_or and bit_xor for bit aggregation function");
            }
            final Method method = builtInMethod.method;
            Expression next = Expressions.call(
                    method.getDeclaringClass(),
                    method.getName(),
                    acc,
                    Expressions.unbox(arg));
            accAdvance(add, acc, next);
        }
    }

    /**
     * Implementor for the {@code GROUPING} aggregate function.
     */
    static class GroupingImplementor implements AggImplementor {
        public List<Type> getStateType(AggContext info) {
            return ImmutableList.of();
        }

        public void implementReset(AggContext info, AggResetContext reset) {
        }

        public void implementAdd(AggContext info, AggAddContext add) {
        }

        public Expression implementResult(AggContext info,
                                          AggResultContext result) {
            final List<Integer> keys;
            switch (info.aggregation().kind) {
                case GROUPING: // "GROUPING(e, ...)", also "GROUPING_ID(e, ...)"
                    keys = result.call().getArgList();
                    break;
                default:
                    throw new AssertionError();
            }
            Expression e = null;
            if (info.groupSets().size() > 1) {
                final List<Integer> keyOrdinals = info.keyOrdinals();
                long x = 1L << (keys.size() - 1);
                for (int k : keys) {
                    final int i = keyOrdinals.indexOf(k);
                    assert i >= 0;
                    final Expression e2 =
                            Expressions.condition(result.keyField(keyOrdinals.size() + i),
                                    Expressions.constant(x),
                                    Expressions.constant(0L));
                    if (e == null) {
                        e = e2;
                    } else {
                        e = Expressions.add(e, e2);
                    }
                    x >>= 1;
                }
            }
            return e != null ? e : Expressions.constant(0, info.returnType());
        }
    }

    /**
     * Implementor for user-defined aggregate functions.
     */
    public static class UserDefinedAggReflectiveImplementor
            extends StrictAggImplementor {
        private final AggregateFunctionImpl afi;

        public UserDefinedAggReflectiveImplementor(AggregateFunctionImpl afi) {
            this.afi = afi;
        }

        @Override
        public List<Type> getNotNullState(AggContext info) {
            if (afi.isStatic) {
                return Collections.singletonList(afi.accumulatorType);
            }
            return Arrays.asList(afi.accumulatorType, afi.declaringClass);
        }

        @Override
        protected void implementNotNullReset(AggContext info,
                                             AggResetContext reset) {
            List<Expression> acc = reset.accumulator();
            if (!afi.isStatic) {
                reset.currentBlock().add(
                        Expressions.statement(
                                Expressions.assign(acc.get(1),
                                        Expressions.new_(afi.declaringClass))));
            }
            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(acc.get(0),
                                    Expressions.call(afi.isStatic
                                            ? null
                                            : acc.get(1), afi.initMethod))));
        }

        @Override
        protected void implementNotNullAdd(AggContext info,
                                           AggAddContext add) {
            List<Expression> acc = add.accumulator();
            List<Expression> aggArgs = add.arguments();
            List<Expression> args = new ArrayList<>(aggArgs.size() + 1);
            args.add(acc.get(0));
            args.addAll(aggArgs);
            add.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(acc.get(0),
                                    Expressions.call(afi.isStatic ? null : acc.get(1), afi.addMethod,
                                            args))));
        }

        @Override
        protected Expression implementNotNullResult(AggContext info,
                                                    AggResultContext result) {
            List<Expression> acc = result.accumulator();
            return Expressions.call(
                    afi.isStatic ? null : acc.get(1), afi.resultMethod, acc.get(0));
        }
    }

    /**
     * Implementor for the {@code RANK} windowed aggregate function.
     */
    static class RankImplementor extends StrictWinAggImplementor {
        @Override
        protected void implementNotNullAdd(WinAggContext info,
                                           WinAggAddContext add) {
            Expression acc = add.accumulator().get(0);
            // This is an example of the generated code
            if (false) {
                new Object() {
                    int curentPosition; // position in for-win-agg-loop
                    int startIndex;     // index of start of window
                    Comparable[] rows;  // accessed via WinAggAddContext.compareRows

                    {
                        if (curentPosition > startIndex) {
                            if (rows[curentPosition - 1].compareTo(rows[curentPosition])
                                    > 0) {
                                // update rank
                            }
                        }
                    }
                };
            }
            BlockBuilder builder = add.nestBlock();
            add.currentBlock().add(
                    Expressions.ifThen(
                            Expressions.lessThan(
                                    add.compareRows(
                                            Expressions.subtract(add.currentPosition(),
                                                    Expressions.constant(1)),
                                            add.currentPosition()),
                                    Expressions.constant(0)),
                            Expressions.statement(
                                    Expressions.assign(acc, computeNewRank(acc, add)))));
            add.exitBlock();
            add.currentBlock().add(
                    Expressions.ifThen(
                            Expressions.greaterThan(add.currentPosition(),
                                    add.startIndex()),
                            builder.toBlock()));
        }

        protected Expression computeNewRank(Expression acc, WinAggAddContext add) {
            Expression pos = add.currentPosition();
            if (!add.startIndex().equals(Expressions.constant(0))) {
                // In general, currentPosition-startIndex should be used
                // However, rank/dense_rank does not allow preceding/following clause
                // so we always result in startIndex==0.
                pos = Expressions.subtract(pos, add.startIndex());
            }
            return pos;
        }

        @Override
        protected Expression implementNotNullResult(
                WinAggContext info, WinAggResultContext result) {
            // Rank is 1-based
            return Expressions.add(super.implementNotNullResult(info, result),
                    Expressions.constant(1));
        }
    }

    /**
     * Implementor for the {@code DENSE_RANK} windowed aggregate function.
     */
    static class DenseRankImplementor extends RankImplementor {
        @Override
        protected Expression computeNewRank(Expression acc,
                                            WinAggAddContext add) {
            return Expressions.add(acc, Expressions.constant(1));
        }
    }

    /**
     * Implementor for the {@code ROW_NUMBER} windowed aggregate function.
     */
    static class RowNumberImplementor extends StrictWinAggImplementor {
        @Override
        public List<Type> getNotNullState(WinAggContext info) {
            return Collections.emptyList();
        }

        @Override
        protected void implementNotNullAdd(WinAggContext info,
                                           WinAggAddContext add) {
            // no op
        }

        @Override
        protected Expression implementNotNullResult(
                WinAggContext info, WinAggResultContext result) {
            // Window cannot be empty since ROWS/RANGE is not possible for ROW_NUMBER
            return Expressions.add(
                    Expressions.subtract(result.index(), result.startIndex()),
                    Expressions.constant(1));
        }
    }

    /**
     * Implementor for the {@code JSON_OBJECTAGG} aggregate function.
     */
    static class JsonObjectAggImplementor implements AggImplementor {
        private final Method m;

        JsonObjectAggImplementor(Method m) {
            this.m = m;
        }

        static Supplier<JsonObjectAggImplementor> supplierFor(Method m) {
            return () -> new JsonObjectAggImplementor(m);
        }

        @Override
        public List<Type> getStateType(AggContext info) {
            return Collections.singletonList(Map.class);
        }

        @Override
        public void implementReset(AggContext info,
                                   AggResetContext reset) {
            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(reset.accumulator().get(0),
                                    Expressions.new_(HashMap.class))));
        }

        @Override
        public void implementAdd(AggContext info, AggAddContext add) {
            final SqlJsonObjectAggAggFunction function =
                    (SqlJsonObjectAggAggFunction) info.aggregation();
            add.currentBlock().add(
                    Expressions.statement(
                            Expressions.call(m,
                                    Iterables.concat(
                                            Collections.singletonList(add.accumulator().get(0)),
                                            add.arguments(),
                                            Collections.singletonList(
                                                    Expressions.constant(function.getNullClause()))))));
        }

        @Override
        public Expression implementResult(AggContext info,
                                          AggResultContext result) {
            return Expressions.call(BuiltInMethod.JSONIZE.method,
                    result.accumulator().get(0));
        }
    }

    /**
     * Implementor for the {@code JSON_ARRAYAGG} aggregate function.
     */
    static class JsonArrayAggImplementor implements AggImplementor {
        private final Method m;

        JsonArrayAggImplementor(Method m) {
            this.m = m;
        }

        static Supplier<JsonArrayAggImplementor> supplierFor(Method m) {
            return () -> new JsonArrayAggImplementor(m);
        }

        @Override
        public List<Type> getStateType(AggContext info) {
            return Collections.singletonList(List.class);
        }

        @Override
        public void implementReset(AggContext info,
                                   AggResetContext reset) {
            reset.currentBlock().add(
                    Expressions.statement(
                            Expressions.assign(reset.accumulator().get(0),
                                    Expressions.new_(ArrayList.class))));
        }

        @Override
        public void implementAdd(AggContext info,
                                 AggAddContext add) {
            final SqlJsonArrayAggAggFunction function =
                    (SqlJsonArrayAggAggFunction) info.aggregation();
            add.currentBlock().add(
                    Expressions.statement(
                            Expressions.call(m,
                                    Iterables.concat(
                                            Collections.singletonList(add.accumulator().get(0)),
                                            add.arguments(),
                                            Collections.singletonList(
                                                    Expressions.constant(function.getNullClause()))))));
        }

        @Override
        public Expression implementResult(AggContext info,
                                          AggResultContext result) {
            return Expressions.call(BuiltInMethod.JSONIZE.method,
                    result.accumulator().get(0));
        }
    }

    /**
     * Implementor for the {@code TRIM} function.
     */
    private static class TrimImplementor extends AbstractRexCallImplementor {
        TrimImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "trim";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            final boolean strict = !translator.conformance.allowExtendedTrim();
            final Object value = translator.getLiteralValue(argValueList.get(0));
            SqlTrimFunction.Flag flag = (SqlTrimFunction.Flag) value;
            return Expressions.call(
                    BuiltInMethod.TRIM.method,
                    Expressions.constant(
                            flag == SqlTrimFunction.Flag.BOTH
                                    || flag == SqlTrimFunction.Flag.LEADING),
                    Expressions.constant(
                            flag == SqlTrimFunction.Flag.BOTH
                                    || flag == SqlTrimFunction.Flag.TRAILING),
                    argValueList.get(1),
                    argValueList.get(2),
                    Expressions.constant(strict));
        }
    }

    /**
     * Implementor for the {@code MONTHNAME} and {@code DAYNAME} functions.
     * Each takes a {@link java.util.Locale} argument.
     */
    private static class PeriodNameImplementor extends MethodNameImplementor {
        private final BuiltInMethod timestampMethod;
        private final BuiltInMethod dateMethod;

        PeriodNameImplementor(String methodName, BuiltInMethod timestampMethod,
                              BuiltInMethod dateMethod) {
            super(methodName, NullPolicy.STRICT, false);
            this.timestampMethod = timestampMethod;
            this.dateMethod = dateMethod;
        }

        @Override
        String getVariableName() {
            return "periodName";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            Expression operand = argValueList.get(0);
            final RelDataType type = call.operands.get(0).getType();
            switch (type.getSqlTypeName()) {
                case TIMESTAMP:
                    return getExpression(translator, operand, timestampMethod);
                case DATE:
                    return getExpression(translator, operand, dateMethod);
                default:
                    throw new AssertionError("unknown type " + type);
            }
        }

        protected Expression getExpression(MycatRexToLixTranslator translator,
                                           Expression operand, BuiltInMethod builtInMethod) {
            final MethodCallExpression locale =
                    Expressions.call(BuiltInMethod.LOCALE.method, translator.getRoot());
            return Expressions.call(builtInMethod.method.getDeclaringClass(),
                    builtInMethod.method.getName(), operand, locale);
        }
    }

    /**
     * Implementor for the {@code FLOOR} and {@code CEIL} functions.
     */
    private static class FloorImplementor extends MethodNameImplementor {
        final Method timestampMethod;
        final Method dateMethod;

        FloorImplementor(String methodName, Method timestampMethod,
                         Method dateMethod) {
            super(methodName, NullPolicy.STRICT, false);
            this.timestampMethod = timestampMethod;
            this.dateMethod = dateMethod;
        }

        @Override
        String getVariableName() {
            return "floor";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            switch (call.getOperands().size()) {
                case 1:
                    switch (call.getType().getSqlTypeName()) {
                        case BIGINT:
                        case INTEGER:
                        case SMALLINT:
                        case TINYINT:
                            return argValueList.get(0);
                        default:
                            return super.implementSafe(translator, call, argValueList);
                    }

                case 2:
                    final Type type;
                    final Method floorMethod;
                    final boolean preFloor;
                    Expression operand = argValueList.get(0);
                    switch (call.getType().getSqlTypeName()) {
                        case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                            operand = Expressions.call(
                                    BuiltInMethod.TIMESTAMP_WITH_LOCAL_TIME_ZONE_TO_TIMESTAMP.method,
                                    operand,
                                    Expressions.call(BuiltInMethod.TIME_ZONE.method, translator.getRoot()));
                            // fall through
                        case TIMESTAMP:
                            type = long.class;
                            floorMethod = timestampMethod;
                            preFloor = true;
                            break;
                        default:
                            type = int.class;
                            floorMethod = dateMethod;
                            preFloor = false;
                    }
                    final TimeUnitRange timeUnitRange =
                            (TimeUnitRange) translator.getLiteralValue(argValueList.get(1));
                    switch (timeUnitRange) {
                        case YEAR:
                        case QUARTER:
                        case MONTH:
                        case WEEK:
                        case DAY:
                            final Expression operand1 =
                                    preFloor ? call(operand, type, TimeUnit.DAY) : operand;
                            return Expressions.call(floorMethod,
                                    translator.getLiteral(argValueList.get(1)), operand1);
                        case NANOSECOND:
                        default:
                            return call(operand, type, timeUnitRange.startUnit);
                    }

                default:
                    throw new AssertionError();
            }
        }

        private Expression call(Expression operand, Type type,
                                TimeUnit timeUnit) {
            return Expressions.call(SqlFunctions.class, methodName,
                    EnumUtils.convert(operand, type),
                    EnumUtils.convert(
                            Expressions.constant(timeUnit.multiplier), type));
        }
    }

    /**
     * Implementor for a function that generates calls to a given method.
     */
    private static class MethodImplementor extends AbstractRexCallImplementor {
        protected final Method method;

        MethodImplementor(Method method, NullPolicy nullPolicy, boolean harmonize) {
            super(nullPolicy, harmonize);
            this.method = method;
        }

        @Override
        String getVariableName() {
            return "method_call";
        }

        @Override
        Expression implementSafe(MycatRexToLixTranslator translator,
                                 RexCall call, List<Expression> argValueList) {
            final Expression expression;
            Class clazz = method.getDeclaringClass();
            if (Modifier.isStatic(method.getModifiers())) {
                expression = EnumUtils.call(clazz, method.getName(), argValueList);
            } else {
                expression = EnumUtils.call(clazz, method.getName(),
                        Util.skip(argValueList, 1), argValueList.get(0));
            }
            return expression;
        }
    }

    /**
     * Implementor for JSON_VALUE function, convert to solid format
     * "JSON_VALUE(json_doc, path, empty_behavior, empty_default, error_behavior, error default)"
     * in order to simplify the runtime implementation.
     *
     * <p>We should avoid this when we support
     * variable arguments function.
     */
    private static class JsonValueImplementor extends MethodImplementor {
        JsonValueImplementor(Method method) {
            super(method, NullPolicy.ARG0, false);
        }

        @Override
        Expression implementSafe(MycatRexToLixTranslator translator,
                                 RexCall call, List<Expression> argValueList) {
            final Expression expression;
            final List<Expression> newOperands = new ArrayList<>();
            newOperands.add(argValueList.get(0));
            newOperands.add(argValueList.get(1));
            List<Expression> leftExprs = Util.skip(argValueList, 2);
            // Default value for JSON_VALUE behaviors.
            Expression emptyBehavior = Expressions.constant(SqlJsonValueEmptyOrErrorBehavior.NULL);
            Expression defaultValueOnEmpty = Expressions.constant(null);
            Expression errorBehavior = Expressions.constant(SqlJsonValueEmptyOrErrorBehavior.NULL);
            Expression defaultValueOnError = Expressions.constant(null);
            // Patched up with user defines.
            if (leftExprs.size() > 0) {
                for (int i = 0; i < leftExprs.size(); i++) {
                    Expression expr = leftExprs.get(i);
                    final Object exprVal = translator.getLiteralValue(expr);
                    if (exprVal != null) {
                        int defaultSymbolIdx = i - 2;
                        if (exprVal == SqlJsonEmptyOrError.EMPTY) {
                            if (defaultSymbolIdx >= 0
                                    && translator.getLiteralValue(leftExprs.get(defaultSymbolIdx))
                                    == SqlJsonValueEmptyOrErrorBehavior.DEFAULT) {
                                defaultValueOnEmpty = leftExprs.get(i - 1);
                                emptyBehavior = leftExprs.get(defaultSymbolIdx);
                            } else {
                                emptyBehavior = leftExprs.get(i - 1);
                            }
                        } else if (exprVal == SqlJsonEmptyOrError.ERROR) {
                            if (defaultSymbolIdx >= 0
                                    && translator.getLiteralValue(leftExprs.get(defaultSymbolIdx))
                                    == SqlJsonValueEmptyOrErrorBehavior.DEFAULT) {
                                defaultValueOnError = leftExprs.get(i - 1);
                                errorBehavior = leftExprs.get(defaultSymbolIdx);
                            } else {
                                errorBehavior = leftExprs.get(i - 1);
                            }
                        }
                    }
                }
            }
            newOperands.add(emptyBehavior);
            newOperands.add(defaultValueOnEmpty);
            newOperands.add(errorBehavior);
            newOperands.add(defaultValueOnError);
            Class clazz = method.getDeclaringClass();
            expression = EnumUtils.call(clazz, method.getName(), newOperands);

            final Type returnType =
                    translator.typeFactory.getJavaClass(call.getType());
            return EnumUtils.convert(expression, returnType);
        }
    }

    /**
     * Implementor for SQL functions that generates calls to a given method name.
     *
     * <p>Use this, as opposed to {@link MethodImplementor}, if the SQL function
     * is overloaded; then you can use one implementor for several overloads.
     */
    private static class MethodNameImplementor extends AbstractRexCallImplementor {
        protected final String methodName;

        MethodNameImplementor(String methodName,
                              NullPolicy nullPolicy, boolean harmonize) {
            super(nullPolicy, harmonize);
            this.methodName = methodName;
        }

        @Override
        String getVariableName() {
            return "method_name_call";
        }

        @Override
        Expression implementSafe(MycatRexToLixTranslator translator,
                                 RexCall call, List<Expression> argValueList) {
            return EnumUtils.call(
                    SqlFunctions.class,
                    methodName,
                    argValueList);
        }
    }

    /**
     * Implementor for binary operators.
     */
    private static class BinaryImplementor extends AbstractRexCallImplementor {
        /**
         * Types that can be arguments to comparison operators such as
         * {@code <}.
         */
        private static final List<Primitive> COMP_OP_TYPES =
                ImmutableList.of(
                        Primitive.BYTE,
                        Primitive.CHAR,
                        Primitive.SHORT,
                        Primitive.INT,
                        Primitive.LONG,
                        Primitive.FLOAT,
                        Primitive.DOUBLE);

        private static final List<SqlBinaryOperator> COMPARISON_OPERATORS =
                ImmutableList.of(
                        SqlStdOperatorTable.LESS_THAN,
                        SqlStdOperatorTable.LESS_THAN_OR_EQUAL,
                        SqlStdOperatorTable.GREATER_THAN,
                        SqlStdOperatorTable.GREATER_THAN_OR_EQUAL);

        private static final List<SqlBinaryOperator> EQUALS_OPERATORS =
                ImmutableList.of(
                        SqlStdOperatorTable.EQUALS,
                        SqlStdOperatorTable.NOT_EQUALS);

        public static final String METHOD_POSTFIX_FOR_ANY_TYPE = "Any";

        private final ExpressionType expressionType;
        private final String backupMethodName;

        BinaryImplementor(NullPolicy nullPolicy, boolean harmonize,
                          ExpressionType expressionType, String backupMethodName) {
            super(nullPolicy, harmonize);
            this.expressionType = expressionType;
            this.backupMethodName = backupMethodName;
        }

        @Override
        String getVariableName() {
            return "binary_call";
        }

        @Override
        Expression implementSafe(
                final MycatRexToLixTranslator translator,
                final RexCall call,
                final List<Expression> argValueList) {
            // neither nullable:
            //   return x OP y
            // x nullable
            //   null_returns_null
            //     return x == null ? null : x OP y
            //   ignore_null
            //     return x == null ? null : y
            // x, y both nullable
            //   null_returns_null
            //     return x == null || y == null ? null : x OP y
            //   ignore_null
            //     return x == null ? y : y == null ? x : x OP y
            if (backupMethodName != null) {
                // If one or both operands have ANY type, use the late-binding backup
                // method.
                if (anyAnyOperands(call)) {
                    return callBackupMethodAnyType(translator, call, argValueList);
                }

                final Type type0 = argValueList.get(0).getType();
                final Type type1 = argValueList.get(1).getType();
                final SqlBinaryOperator op = (SqlBinaryOperator) call.getOperator();
                final RelDataType relDataType0 = call.getOperands().get(0).getType();
                final Expression fieldComparator = generateCollatorExpression(relDataType0.getCollation());
                if (fieldComparator != null) {
                    argValueList.add(fieldComparator);
                }
                final Primitive primitive = Primitive.ofBoxOr(type0);
                if (primitive == null
                        || type1 == BigDecimal.class
                        || COMPARISON_OPERATORS.contains(op)
                        && !COMP_OP_TYPES.contains(primitive)) {
                    return Expressions.call(SqlFunctions.class, backupMethodName,
                            argValueList);
                }
                // When checking equals or not equals on two primitive boxing classes
                // (i.e. Long x, Long y), we should fall back to call `SqlFunctions.eq(x, y)`
                // or `SqlFunctions.ne(x, y)`, rather than `x == y`
                final Primitive boxPrimitive0 = Primitive.ofBox(type0);
                final Primitive boxPrimitive1 = Primitive.ofBox(type1);
                if (EQUALS_OPERATORS.contains(op)
                        && boxPrimitive0 != null && boxPrimitive1 != null) {
                    return Expressions.call(SqlFunctions.class, backupMethodName,
                            argValueList);
                }
            }
            return Expressions.makeBinary(expressionType,
                    argValueList.get(0), argValueList.get(1));
        }

        /**
         * Returns whether any of a call's operands have ANY type.
         */
        private boolean anyAnyOperands(RexCall call) {
            for (RexNode operand : call.operands) {
                if (operand.getType().getSqlTypeName() == SqlTypeName.ANY) {
                    return true;
                }
            }
            return false;
        }

        private Expression callBackupMethodAnyType(MycatRexToLixTranslator translator,
                                                   RexCall call, List<Expression> expressions) {
            final String backupMethodNameForAnyType =
                    backupMethodName + METHOD_POSTFIX_FOR_ANY_TYPE;

            // one or both of parameter(s) is(are) ANY type
            final Expression expression0 = maybeBox(expressions.get(0));
            final Expression expression1 = maybeBox(expressions.get(1));
            return Expressions.call(SqlFunctions.class, backupMethodNameForAnyType,
                    expression0, expression1);
        }

        private Expression maybeBox(Expression expression) {
            final Primitive primitive = Primitive.of(expression.getType());
            if (primitive != null) {
                expression = Expressions.box(expression, primitive);
            }
            return expression;
        }
    }

    /**
     * Implementor for unary operators.
     */
    private static class UnaryImplementor extends AbstractRexCallImplementor {
        private final ExpressionType expressionType;

        UnaryImplementor(ExpressionType expressionType, NullPolicy nullPolicy) {
            super(nullPolicy, false);
            this.expressionType = expressionType;
        }

        @Override
        String getVariableName() {
            return "unary_call";
        }

        @Override
        Expression implementSafe(MycatRexToLixTranslator translator,
                                 RexCall call, List<Expression> argValueList) {
            final Expression argValue = argValueList.get(0);
            final UnaryExpression e = Expressions.makeUnary(expressionType, argValue);
            if (e.type.equals(argValue.type)) {
                return e;
            }
            // Certain unary operators do not preserve type. For example, the "-"
            // operator applied to a "byte" expression returns an "int".
            return Expressions.convert_(e, argValue.type);
        }
    }

    /**
     * Implementor for the {@code EXTRACT(unit FROM datetime)} function.
     */
    private static class ExtractImplementor extends AbstractRexCallImplementor {
        ExtractImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "extract";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            final TimeUnitRange timeUnitRange =
                    (TimeUnitRange) translator.getLiteralValue(argValueList.get(0));
            final TimeUnit unit = timeUnitRange.startUnit;
            Expression operand = argValueList.get(1);
            final SqlTypeName sqlTypeName =
                    call.operands.get(1).getType().getSqlTypeName();
            switch (unit) {
                case MILLENNIUM:
                case CENTURY:
                case YEAR:
                case QUARTER:
                case MONTH:
                case DAY:
                case DOW:
                case DECADE:
                case DOY:
                case ISODOW:
                case ISOYEAR:
                case WEEK:
                    switch (sqlTypeName) {
                        case INTERVAL_YEAR:
                        case INTERVAL_YEAR_MONTH:
                        case INTERVAL_MONTH:
                        case INTERVAL_DAY:
                        case INTERVAL_DAY_HOUR:
                        case INTERVAL_DAY_MINUTE:
                        case INTERVAL_DAY_SECOND:
                        case INTERVAL_HOUR:
                        case INTERVAL_HOUR_MINUTE:
                        case INTERVAL_HOUR_SECOND:
                        case INTERVAL_MINUTE:
                        case INTERVAL_MINUTE_SECOND:
                        case INTERVAL_SECOND:
                            break;
                        case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                            operand = Expressions.call(
                                    BuiltInMethod.TIMESTAMP_WITH_LOCAL_TIME_ZONE_TO_TIMESTAMP.method,
                                    operand,
                                    Expressions.call(BuiltInMethod.TIME_ZONE.method, translator.getRoot()));
                            // fall through
                        case TIMESTAMP:
                            operand = Expressions.call(BuiltInMethod.FLOOR_DIV.method,
                                    operand, Expressions.constant(TimeUnit.DAY.multiplier.longValue()));
                            // fall through
                        case DATE:
                            return Expressions.call(BuiltInMethod.UNIX_DATE_EXTRACT.method,
                                    argValueList.get(0), operand);
                        default:
                            throw new AssertionError("unexpected " + sqlTypeName);
                    }
                    break;
                case MILLISECOND:
                case MICROSECOND:
                case NANOSECOND:
                    if (sqlTypeName == SqlTypeName.DATE) {
                        return Expressions.constant(0L);
                    }
                    operand = mod(operand, TimeUnit.MINUTE.multiplier.longValue());
                    return Expressions.multiply(
                            operand, Expressions.constant((long) (1 / unit.multiplier.doubleValue())));
                case EPOCH:
                    switch (sqlTypeName) {
                        case DATE:
                            // convert to milliseconds
                            operand = Expressions.multiply(operand,
                                    Expressions.constant(TimeUnit.DAY.multiplier.longValue()));
                            // fall through
                        case TIMESTAMP:
                            // convert to seconds
                            return Expressions.divide(operand,
                                    Expressions.constant(TimeUnit.SECOND.multiplier.longValue()));
                        case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                            operand = Expressions.call(
                                    BuiltInMethod.TIMESTAMP_WITH_LOCAL_TIME_ZONE_TO_TIMESTAMP.method,
                                    operand,
                                    Expressions.call(BuiltInMethod.TIME_ZONE.method, translator.getRoot()));
                            return Expressions.divide(operand,
                                    Expressions.constant(TimeUnit.SECOND.multiplier.longValue()));
                        case INTERVAL_YEAR:
                        case INTERVAL_YEAR_MONTH:
                        case INTERVAL_MONTH:
                        case INTERVAL_DAY:
                        case INTERVAL_DAY_HOUR:
                        case INTERVAL_DAY_MINUTE:
                        case INTERVAL_DAY_SECOND:
                        case INTERVAL_HOUR:
                        case INTERVAL_HOUR_MINUTE:
                        case INTERVAL_HOUR_SECOND:
                        case INTERVAL_MINUTE:
                        case INTERVAL_MINUTE_SECOND:
                        case INTERVAL_SECOND:
                            // no convertlet conversion, pass it as extract
                            throw new AssertionError("unexpected " + sqlTypeName);
                    }
                    break;
                case HOUR:
                case MINUTE:
                case SECOND:
                    switch (sqlTypeName) {
                        case DATE:
                            return Expressions.multiply(operand, Expressions.constant(0L));
                    }
                    break;
            }

            operand = mod(operand, getFactor(unit));
            if (unit == TimeUnit.QUARTER) {
                operand = Expressions.subtract(operand, Expressions.constant(1L));
            }
            operand = Expressions.divide(operand,
                    Expressions.constant(unit.multiplier.longValue()));
            if (unit == TimeUnit.QUARTER) {
                operand = Expressions.add(operand, Expressions.constant(1L));
            }
            return operand;
        }
    }

    private static Expression mod(Expression operand, long factor) {
        if (factor == 1L) {
            return operand;
        } else {
            return Expressions.call(BuiltInMethod.FLOOR_MOD.method,
                    operand, Expressions.constant(factor));
        }
    }

    private static long getFactor(TimeUnit unit) {
        switch (unit) {
            case DAY:
                return 1L;
            case HOUR:
                return TimeUnit.DAY.multiplier.longValue();
            case MINUTE:
                return TimeUnit.HOUR.multiplier.longValue();
            case SECOND:
                return TimeUnit.MINUTE.multiplier.longValue();
            case MILLISECOND:
                return TimeUnit.SECOND.multiplier.longValue();
            case MONTH:
                return TimeUnit.YEAR.multiplier.longValue();
            case QUARTER:
                return TimeUnit.YEAR.multiplier.longValue();
            case YEAR:
            case DECADE:
            case CENTURY:
            case MILLENNIUM:
                return 1L;
            default:
                throw Util.unexpected(unit);
        }
    }

    /**
     * Implementor for the SQL {@code COALESCE} operator.
     */
    private static class CoalesceImplementor extends AbstractRexCallImplementor {
        CoalesceImplementor() {
            super(NullPolicy.NONE, false);
        }

        @Override
        String getVariableName() {
            return "coalesce";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return implementRecurse(translator, argValueList);
        }

        private Expression implementRecurse(MycatRexToLixTranslator translator,
                                            final List<Expression> argValueList) {
            if (argValueList.size() == 1) {
                return argValueList.get(0);
            } else {
                return Expressions.condition(
                        translator.checkNotNull(argValueList.get(0)),
                        argValueList.get(0),
                        implementRecurse(translator, Util.skip(argValueList)));
            }
        }
    }

    /**
     * Implementor for the SQL {@code CAST} operator.
     */
    private static class CastImplementor extends AbstractRexCallImplementor {
        CastImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "cast";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            assert call.getOperands().size() == 1;
            final RelDataType sourceType = call.getOperands().get(0).getType();

            // Short-circuit if no cast is required
            RexNode arg = call.getOperands().get(0);
            if (call.getType().equals(sourceType)) {
                // No cast required, omit cast
                return argValueList.get(0);
            }
            if (SqlTypeUtil.equalSansNullability(translator.typeFactory,
                    call.getType(), arg.getType())
                    && translator.deref(arg) instanceof RexLiteral) {
                return MycatRexToLixTranslator.translateLiteral(
                        (RexLiteral) translator.deref(arg), call.getType(),
                        translator.typeFactory, NullAs.NULL);
            }
            final RelDataType targetType =
                    nullifyType(translator.typeFactory, call.getType(), false);
            return translator.translateCast(sourceType,
                    targetType, argValueList.get(0));
        }

        private RelDataType nullifyType(JavaTypeFactory typeFactory,
                                        final RelDataType type, final boolean nullable) {
            if (type instanceof RelDataTypeFactoryImpl.JavaType) {
                final Primitive primitive = Primitive.ofBox(
                        ((RelDataTypeFactoryImpl.JavaType) type).getJavaClass());
                if (primitive != null) {
                    return typeFactory.createJavaType(primitive.primitiveClass);
                }
            }
            return typeFactory.createTypeWithNullability(type, nullable);
        }
    }

    /**
     * Implementor for the {@code REINTERPRET} internal SQL operator.
     */
    private static class ReinterpretImplementor extends AbstractRexCallImplementor {
        ReinterpretImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "reInterpret";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            assert call.getOperands().size() == 1;
            return argValueList.get(0);
        }
    }

    /**
     * Implementor for a value-constructor.
     */
    private static class ValueConstructorImplementor
            extends AbstractRexCallImplementor {

        ValueConstructorImplementor() {
            super(NullPolicy.NONE, false);
        }

        @Override
        String getVariableName() {
            return "value_constructor";
        }

        @Override
        Expression implementSafe(MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            SqlKind kind = call.getOperator().getKind();
            final BlockBuilder blockBuilder = translator.getBlockBuilder();
            switch (kind) {
                case MAP_VALUE_CONSTRUCTOR:
                    Expression map =
                            blockBuilder.append("map", Expressions.new_(LinkedHashMap.class),
                                    false);
                    for (int i = 0; i < argValueList.size(); i++) {
                        Expression key = argValueList.get(i++);
                        Expression value = argValueList.get(i);
                        blockBuilder.add(
                                Expressions.statement(
                                        Expressions.call(map, BuiltInMethod.MAP_PUT.method,
                                                Expressions.box(key), Expressions.box(value))));
                    }
                    return map;
                case ARRAY_VALUE_CONSTRUCTOR:
                    Expression lyst =
                            blockBuilder.append("list", Expressions.new_(ArrayList.class),
                                    false);
                    for (Expression value : argValueList) {
                        blockBuilder.add(
                                Expressions.statement(
                                        Expressions.call(lyst, BuiltInMethod.COLLECTION_ADD.method,
                                                Expressions.box(value))));
                    }
                    return lyst;
                default:
                    throw new AssertionError("unexpected: " + kind);
            }
        }
    }

    /**
     * Implementor for the {@code ITEM} SQL operator.
     */
    private static class ItemImplementor extends AbstractRexCallImplementor {
        ItemImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "item";
        }

        // Since we follow PostgreSQL's semantics that an out-of-bound reference
        // returns NULL, x[y] can return null even if x and y are both NOT NULL.
        // (In SQL standard semantics, an out-of-bound reference to an array
        // throws an exception.)
        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            final MethodImplementor implementor =
                    getImplementor(call.getOperands().get(0).getType().getSqlTypeName());
            return implementor.implementSafe(translator, call, argValueList);
        }

        private MethodImplementor getImplementor(SqlTypeName sqlTypeName) {
            switch (sqlTypeName) {
                case ARRAY:
                    return new MethodImplementor(BuiltInMethod.ARRAY_ITEM.method, nullPolicy, false);
                case MAP:
                    return new MethodImplementor(BuiltInMethod.MAP_ITEM.method, nullPolicy, false);
                default:
                    return new MethodImplementor(BuiltInMethod.ANY_ITEM.method, nullPolicy, false);
            }
        }
    }

    /**
     * Implementor for SQL system functions.
     *
     * <p>Several of these are represented internally as constant values, set
     * per execution.
     */
    private static class SystemFunctionImplementor
            extends AbstractRexCallImplementor {
        SystemFunctionImplementor() {
            super(NullPolicy.NONE, false);
        }

        @Override
        String getVariableName() {
            return "system_func";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            final SqlOperator op = call.getOperator();
            final Expression root = translator.getRoot();
            if (op == CURRENT_USER
                    || op == SESSION_USER
                    || op == USER) {
                return Expressions.call(BuiltInMethod.USER.method, root);
            } else if (op == SYSTEM_USER) {
                return Expressions.call(BuiltInMethod.SYSTEM_USER.method, root);
            } else if (op == CURRENT_PATH
                    || op == CURRENT_ROLE
                    || op == CURRENT_CATALOG) {
                // By default, the CURRENT_ROLE and CURRENT_CATALOG functions return the
                // empty string because a role or a catalog has to be set explicitly.
                return Expressions.constant("");
            } else if (op == CURRENT_TIMESTAMP) {
                return Expressions.call(BuiltInMethod.CURRENT_TIMESTAMP.method, root);
            } else if (op == CURRENT_TIME) {
                return Expressions.call(BuiltInMethod.CURRENT_TIME.method, root);
            } else if (op == CURRENT_DATE) {
                return Expressions.call(BuiltInMethod.CURRENT_DATE.method, root);
            } else if (op == LOCALTIMESTAMP) {
                return Expressions.call(BuiltInMethod.LOCAL_TIMESTAMP.method, root);
            } else if (op == LOCALTIME) {
                return Expressions.call(BuiltInMethod.LOCAL_TIME.method, root);
            } else {
                throw new AssertionError("unknown function " + op);
            }
        }
    }

    /**
     * Implementor for the {@code NOT} operator.
     */
    private static class NotImplementor extends AbstractRexCallImplementor {
        private AbstractRexCallImplementor implementor;

        private NotImplementor(AbstractRexCallImplementor implementor) {
            super(null, false);
            this.implementor = implementor;
        }

        static AbstractRexCallImplementor of(AbstractRexCallImplementor implementor) {
            return new NotImplementor(implementor);
        }

        @Override
        String getVariableName() {
            return "not";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            final Expression expression =
                    implementor.implementSafe(translator, call, argValueList);
            return Expressions.not(expression);
        }
    }

    /**
     * Implementor for various datetime arithmetic.
     */
    private static class DatetimeArithmeticImplementor
            extends AbstractRexCallImplementor {
        DatetimeArithmeticImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "dateTime_arithmetic";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            final RexNode operand0 = call.getOperands().get(0);
            Expression trop0 = argValueList.get(0);
            final SqlTypeName typeName1 =
                    call.getOperands().get(1).getType().getSqlTypeName();
            Expression trop1 = argValueList.get(1);
            final SqlTypeName typeName = call.getType().getSqlTypeName();
            switch (operand0.getType().getSqlTypeName()) {
                case DATE:
                    switch (typeName) {
                        case TIMESTAMP:
                            trop0 = Expressions.convert_(
                                    Expressions.multiply(trop0,
                                            Expressions.constant(DateTimeUtils.MILLIS_PER_DAY)),
                                    long.class);
                            break;
                        default:
                            switch (typeName1) {
                                case INTERVAL_DAY:
                                case INTERVAL_DAY_HOUR:
                                case INTERVAL_DAY_MINUTE:
                                case INTERVAL_DAY_SECOND:
                                case INTERVAL_HOUR:
                                case INTERVAL_HOUR_MINUTE:
                                case INTERVAL_HOUR_SECOND:
                                case INTERVAL_MINUTE:
                                case INTERVAL_MINUTE_SECOND:
                                case INTERVAL_SECOND:
                                    trop1 = Expressions.convert_(
                                            Expressions.divide(trop1,
                                                    Expressions.constant(DateTimeUtils.MILLIS_PER_DAY)),
                                            int.class);
                            }
                    }
                    break;
                case TIME:
                    trop1 = Expressions.convert_(trop1, int.class);
                    break;
            }
            switch (typeName1) {
                case INTERVAL_YEAR:
                case INTERVAL_YEAR_MONTH:
                case INTERVAL_MONTH:
                    switch (call.getKind()) {
                        case MINUS:
                            trop1 = Expressions.negate(trop1);
                    }
                    switch (typeName) {
                        case TIME:
                            return Expressions.convert_(trop0, long.class);
                        default:
                            final BuiltInMethod method =
                                    operand0.getType().getSqlTypeName() == SqlTypeName.TIMESTAMP
                                            ? BuiltInMethod.ADD_MONTHS
                                            : BuiltInMethod.ADD_MONTHS_INT;
                            return Expressions.call(method.method, trop0, trop1);
                    }

                case INTERVAL_DAY:
                case INTERVAL_DAY_HOUR:
                case INTERVAL_DAY_MINUTE:
                case INTERVAL_DAY_SECOND:
                case INTERVAL_HOUR:
                case INTERVAL_HOUR_MINUTE:
                case INTERVAL_HOUR_SECOND:
                case INTERVAL_MINUTE:
                case INTERVAL_MINUTE_SECOND:
                case INTERVAL_SECOND:
                    switch (call.getKind()) {
                        case MINUS:
                            return normalize(typeName, Expressions.subtract(trop0, trop1));
                        default:
                            return normalize(typeName, Expressions.add(trop0, trop1));
                    }

                default:
                    switch (call.getKind()) {
                        case MINUS:
                            switch (typeName) {
                                case INTERVAL_YEAR:
                                case INTERVAL_YEAR_MONTH:
                                case INTERVAL_MONTH:
                                    return Expressions.call(BuiltInMethod.SUBTRACT_MONTHS.method,
                                            trop0, trop1);
                            }
                            TimeUnit fromUnit =
                                    typeName1 == SqlTypeName.DATE ? TimeUnit.DAY : TimeUnit.MILLISECOND;
                            TimeUnit toUnit = TimeUnit.MILLISECOND;
                            return multiplyDivide(
                                    Expressions.convert_(Expressions.subtract(trop0, trop1),
                                            (Class) long.class),
                                    fromUnit.multiplier, toUnit.multiplier);
                        default:
                            throw new AssertionError(call);
                    }
            }
        }

        /**
         * Normalizes a TIME value into 00:00:00..23:59:39.
         */
        private Expression normalize(SqlTypeName typeName, Expression e) {
            switch (typeName) {
                case TIME:
                    return Expressions.call(BuiltInMethod.FLOOR_MOD.method, e,
                            Expressions.constant(DateTimeUtils.MILLIS_PER_DAY));
                default:
                    return e;
            }
        }
    }

    /**
     * Null-safe implementor of {@code RexCall}s.
     */
    public interface RexCallImplementor {
        MycatRexToLixTranslator.Result implement(
                MycatRexToLixTranslator translator,
                RexCall call,
                List<MycatRexToLixTranslator.Result> arguments);
    }

    /**
     * Abstract implementation of the {@link RexCallImplementor} interface.
     *
     * <p>It is not always safe to execute the {@link RexCall} directly due to
     * the special null arguments. Therefore, the generated code logic is
     * conditional correspondingly.
     *
     * <p>For example, {@code a + b} will generate two declaration statements:
     *
     * <blockquote>
     * <code>
     * final Integer xxx_value = (a_isNull || b_isNull) ? null : plus(a, b);<br>
     * final boolean xxx_isNull = xxx_value == null;
     * </code>
     * </blockquote>
     */
    private abstract static class AbstractRexCallImplementor
            implements RexCallImplementor {
        final NullPolicy nullPolicy;
        private final boolean harmonize;

        AbstractRexCallImplementor(NullPolicy nullPolicy, boolean harmonize) {
            this.nullPolicy = nullPolicy;
            this.harmonize = harmonize;
        }

        @Override
        public MycatRexToLixTranslator.Result implement(
                final MycatRexToLixTranslator translator,
                final RexCall call,
                final List<MycatRexToLixTranslator.Result> arguments) {
            final List<Expression> argIsNullList = new ArrayList<>();
            final List<Expression> argValueList = new ArrayList<>();
            for (MycatRexToLixTranslator.Result result : arguments) {
                argIsNullList.add(result.isNullVariable);
                argValueList.add(result.valueVariable);
            }
            final Expression condition = getCondition(argIsNullList);
            final ParameterExpression valueVariable =
                    genValueStatement(translator, call, argValueList, condition);
            final ParameterExpression isNullVariable =
                    genIsNullStatement(translator, valueVariable);
            return new MycatRexToLixTranslator.Result(isNullVariable, valueVariable);
        }

        // Variable name facilitates reasoning about issues when necessary
        abstract String getVariableName();

        /**
         * Figures out conditional expression according to NullPolicy.
         */
        Expression getCondition(final List<Expression> argIsNullList) {
            if (argIsNullList.size() == 0
                    || nullPolicy == null
                    || nullPolicy == NullPolicy.NONE) {
                return FALSE_EXPR;
            }
            if (nullPolicy == NullPolicy.ARG0) {
                return argIsNullList.get(0);
            }
            return Expressions.foldOr(argIsNullList);
        }

        // E.g., "final Integer xxx_value = (a_isNull || b_isNull) ? null : plus(a, b)"
        private ParameterExpression genValueStatement(
                final MycatRexToLixTranslator translator,
                final RexCall call, final List<Expression> argValueList,
                final Expression condition) {
            List<Expression> optimizedArgValueList = argValueList;
            if (harmonize) {
                optimizedArgValueList =
                        harmonize(optimizedArgValueList, translator, call);
            }
            optimizedArgValueList = unboxIfNecessary(optimizedArgValueList);

            final Expression callValue =
                    implementSafe(translator, call, optimizedArgValueList);

            // In general, RexCall's type is correct for code generation
            // and thus we should ensure the consistency.
            // However, for some special cases (e.g., TableFunction),
            // the implementation's type is correct, we can't convert it.
            final SqlOperator op = call.getOperator();
            final Type returnType = translator.typeFactory.getJavaClass(call.getType());
            final boolean noConvert = (returnType == null)
                    || (returnType == callValue.getType())
                    || (op instanceof SqlUserDefinedTableMacro)
                    || (op instanceof SqlUserDefinedTableFunction);
            final Expression convertedCallValue =
                    noConvert
                            ? callValue
                            : EnumUtils.convert(callValue, returnType);

            final Expression valueExpression =
                    Expressions.condition(condition,
                            getIfTrue(convertedCallValue.getType(), argValueList),
                            convertedCallValue);
            final ParameterExpression value =
                    Expressions.parameter(convertedCallValue.getType(),
                            translator.getBlockBuilder().newName(getVariableName() + "_value"));
            translator.getBlockBuilder().add(
                    Expressions.declare(Modifier.FINAL, value, valueExpression));
            return value;
        }

        Expression getIfTrue(Type type, final List<Expression> argValueList) {
            return getDefaultValue(type);
        }

        // E.g., "final boolean xxx_isNull = xxx_value == null"
        private ParameterExpression genIsNullStatement(
                final MycatRexToLixTranslator translator, final ParameterExpression value) {
            final ParameterExpression isNullVariable =
                    Expressions.parameter(Boolean.TYPE,
                            translator.getBlockBuilder().newName(getVariableName() + "_isNull"));
            final Expression isNullExpression = translator.checkNull(value);
            translator.getBlockBuilder().add(
                    Expressions.declare(Modifier.FINAL, isNullVariable, isNullExpression));
            return isNullVariable;
        }

        /**
         * Ensures that operands have identical type.
         */
        private List<Expression> harmonize(final List<Expression> argValueList,
                                           final MycatRexToLixTranslator translator, final RexCall call) {
            int nullCount = 0;
            final List<RelDataType> types = new ArrayList<>();
            final RelDataTypeFactory typeFactory =
                    translator.builder.getTypeFactory();
            for (RexNode operand : call.getOperands()) {
                RelDataType type = operand.getType();
                type = toSql(typeFactory, type);
                if (translator.isNullable(operand)) {
                    ++nullCount;
                } else {
                    type = typeFactory.createTypeWithNullability(type, false);
                }
                types.add(type);
            }
            if (allSame(types)) {
                // Operands have the same nullability and type. Return them
                // unchanged.
                return argValueList;
            }
            final RelDataType type = typeFactory.leastRestrictive(types);
            if (type == null) {
                // There is no common type. Presumably this is a binary operator with
                // asymmetric arguments (e.g. interval / integer) which is not intended
                // to be harmonized.
                return argValueList;
            }
            assert (nullCount > 0) == type.isNullable();
            final Type javaClass =
                    translator.typeFactory.getJavaClass(type);
            final List<Expression> harmonizedArgValues = new ArrayList<>();
            for (Expression argValue : argValueList) {
                harmonizedArgValues.add(
                        EnumUtils.convert(argValue, javaClass));
            }
            return harmonizedArgValues;
        }

        /**
         * Under null check, it is safe to unbox the operands before entering the
         * implementor.
         */
        private List<Expression> unboxIfNecessary(final List<Expression> argValueList) {
            List<Expression> unboxValueList = argValueList;
            if (nullPolicy == NullPolicy.STRICT || nullPolicy == NullPolicy.ANY
                    || nullPolicy == NullPolicy.SEMI_STRICT) {
                unboxValueList = argValueList.stream()
                        .map(this::unboxExpression)
                        .collect(Collectors.toList());
            }
            if (nullPolicy == NullPolicy.ARG0 && argValueList.size() > 0) {
                final Expression unboxArg0 = unboxExpression(unboxValueList.get(0));
                unboxValueList.set(0, unboxArg0);
            }
            return unboxValueList;
        }

        private Expression unboxExpression(final Expression argValue) {
            Primitive fromBox = Primitive.ofBox(argValue.getType());
            if (fromBox == null || fromBox == Primitive.VOID) {
                return argValue;
            }
            // Optimization: for "long x";
            // "Long.valueOf(x)" generates "x"
            if (argValue instanceof MethodCallExpression) {
                MethodCallExpression mce = (MethodCallExpression) argValue;
                if (mce.method.getName().equals("valueOf") && mce.expressions.size() == 1) {
                    Expression originArg = mce.expressions.get(0);
                    if (Primitive.of(originArg.type) == fromBox) {
                        return originArg;
                    }
                }
            }
            return NullAs.NOT_POSSIBLE.handle(argValue);
        }

        abstract Expression implementSafe(MycatRexToLixTranslator translator,
                                          RexCall call, List<Expression> argValueList);
    }

    /**
     * Implementor for the {@code AND} operator.
     *
     * <p>If any of the arguments are false, result is false;
     * else if any arguments are null, result is null;
     * else true.
     */
    private static class LogicalAndImplementor extends AbstractRexCallImplementor {
        LogicalAndImplementor() {
            super(NullPolicy.NONE, true);
        }

        @Override
        String getVariableName() {
            return "logical_and";
        }

        @Override
        public MycatRexToLixTranslator.Result implement(final MycatRexToLixTranslator translator,
                                                        final RexCall call, final List<MycatRexToLixTranslator.Result> arguments) {
            final List<Expression> argIsNullList = new ArrayList<>();
            for (MycatRexToLixTranslator.Result result : arguments) {
                argIsNullList.add(result.isNullVariable);
            }
            final List<Expression> nullAsTrue =
                    arguments.stream()
                            .map(result ->
                                    Expressions.condition(result.isNullVariable, TRUE_EXPR,
                                            result.valueVariable))
                            .collect(Collectors.toList());
            final Expression hasFalse =
                    Expressions.not(Expressions.foldAnd(nullAsTrue));
            final Expression hasNull = Expressions.foldOr(argIsNullList);
            final Expression callExpression =
                    Expressions.condition(hasFalse, BOXED_FALSE_EXPR,
                            Expressions.condition(hasNull, NULL_EXPR, BOXED_TRUE_EXPR));
            final MycatRexImpTable.NullAs nullAs = translator.isNullable(call)
                    ? MycatRexImpTable.NullAs.NULL : MycatRexImpTable.NullAs.NOT_POSSIBLE;
            final Expression valueExpression = nullAs.handle(callExpression);
            final ParameterExpression valueVariable =
                    Expressions.parameter(valueExpression.getType(),
                            translator.getBlockBuilder().newName(getVariableName() + "_value"));
            final Expression isNullExpression = translator.checkNull(valueVariable);
            final ParameterExpression isNullVariable =
                    Expressions.parameter(Boolean.TYPE,
                            translator.getBlockBuilder().newName(getVariableName() + "_isNull"));
            translator.getBlockBuilder().add(
                    Expressions.declare(Modifier.FINAL, valueVariable, valueExpression));
            translator.getBlockBuilder().add(
                    Expressions.declare(Modifier.FINAL, isNullVariable, isNullExpression));
            return new MycatRexToLixTranslator.Result(isNullVariable, valueVariable);
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return null;
        }
    }

    /**
     * Implementor for the {@code OR} operator.
     *
     * <p>If any of the arguments are true, result is true;
     * else if any arguments are null, result is null;
     * else false.
     */
    private static class LogicalOrImplementor extends AbstractRexCallImplementor {
        LogicalOrImplementor() {
            super(NullPolicy.NONE, true);
        }

        @Override
        String getVariableName() {
            return "logical_or";
        }

        @Override
        public MycatRexToLixTranslator.Result implement(final MycatRexToLixTranslator translator,
                                                        final RexCall call, final List<MycatRexToLixTranslator.Result> arguments) {
            final List<Expression> argIsNullList = new ArrayList<>();
            for (MycatRexToLixTranslator.Result result : arguments) {
                argIsNullList.add(result.isNullVariable);
            }
            final List<Expression> nullAsFalse =
                    arguments.stream()
                            .map(result ->
                                    Expressions.condition(result.isNullVariable, FALSE_EXPR,
                                            result.valueVariable))
                            .collect(Collectors.toList());
            final Expression hasTrue = Expressions.foldOr(nullAsFalse);
            final Expression hasNull = Expressions.foldOr(argIsNullList);
            final Expression callExpression =
                    Expressions.condition(hasTrue, BOXED_TRUE_EXPR,
                            Expressions.condition(hasNull, NULL_EXPR, BOXED_FALSE_EXPR));
            final MycatRexImpTable.NullAs nullAs = translator.isNullable(call)
                    ? MycatRexImpTable.NullAs.NULL : MycatRexImpTable.NullAs.NOT_POSSIBLE;
            final Expression valueExpression = nullAs.handle(callExpression);
            final ParameterExpression valueVariable =
                    Expressions.parameter(valueExpression.getType(),
                            translator.getBlockBuilder().newName(getVariableName() + "_value"));
            final Expression isNullExpression = translator.checkNull(valueExpression);
            final ParameterExpression isNullVariable =
                    Expressions.parameter(Boolean.TYPE,
                            translator.getBlockBuilder().newName(getVariableName() + "_isNull"));
            translator.getBlockBuilder().add(
                    Expressions.declare(Modifier.FINAL, valueVariable, valueExpression));
            translator.getBlockBuilder().add(
                    Expressions.declare(Modifier.FINAL, isNullVariable, isNullExpression));
            return new MycatRexToLixTranslator.Result(isNullVariable, valueVariable);
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return null;
        }
    }

    /**
     * Implementor for the {@code NOT} operator.
     *
     * <p>If any of the arguments are false, result is true;
     * else if any arguments are null, result is null;
     * else false.
     */
    private static class LogicalNotImplementor extends AbstractRexCallImplementor {
        LogicalNotImplementor() {
            super(NullPolicy.NONE, true);
        }

        @Override
        String getVariableName() {
            return "logical_not";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return Expressions.call(BuiltInMethod.NOT.method, argValueList);
        }
    }

    /**
     * Implementation that calls a given {@link java.lang.reflect.Method}.
     *
     * <p>When method is not static, a new instance of the required class is
     * created.
     */
    private static class ReflectiveImplementor extends AbstractRexCallImplementor {
        protected final Method method;

        ReflectiveImplementor(Method method, NullPolicy nullPolicy) {
            super(nullPolicy, false);
            this.method = method;
        }

        @Override
        String getVariableName() {
            return "reflective_" + method.getName();
        }

        @Override
        Expression implementSafe(MycatRexToLixTranslator translator,
                                 RexCall call, List<Expression> argValueList) {
            List<Expression> argValueList0 =
                    EnumUtils.fromInternal(method.getParameterTypes(), argValueList);
            if ((method.getModifiers() & Modifier.STATIC) != 0) {
                return Expressions.call(method, argValueList0);
            } else {
                // The UDF class must have a public zero-args constructor.
                // Assume that the validator checked already.
                final Expression target = Expressions.new_(method.getDeclaringClass());
                return Expressions.call(target, method, argValueList0);
            }
        }
    }

    /**
     * Implementor for the {@code RAND} function.
     */
    private static class RandImplementor extends AbstractRexCallImplementor {
        private final AbstractRexCallImplementor[] implementors = {
                new ReflectiveImplementor(BuiltInMethod.RAND.method, nullPolicy),
                new ReflectiveImplementor(BuiltInMethod.RAND_SEED.method, nullPolicy)
        };

        RandImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "rand";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return implementors[call.getOperands().size()]
                    .implementSafe(translator, call, argValueList);
        }
    }

    /**
     * Implementor for the {@code RAND_INTEGER} function.
     */
    private static class RandIntegerImplementor extends AbstractRexCallImplementor {
        private final AbstractRexCallImplementor[] implementors = {
                null,
                new ReflectiveImplementor(BuiltInMethod.RAND_INTEGER.method, nullPolicy),
                new ReflectiveImplementor(BuiltInMethod.RAND_INTEGER_SEED.method, nullPolicy)
        };

        RandIntegerImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "rand_integer";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return implementors[call.getOperands().size()]
                    .implementSafe(translator, call, argValueList);
        }
    }

    /**
     * Implementor for the {@code PI} operator.
     */
    private static class PiImplementor extends AbstractRexCallImplementor {
        PiImplementor() {
            super(NullPolicy.NONE, false);
        }

        @Override
        String getVariableName() {
            return "pi";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return Expressions.constant(Math.PI);
        }
    }

    /**
     * Implementor for the {@code IS FALSE} SQL operator.
     */
    private static class IsFalseImplementor extends AbstractRexCallImplementor {
        IsFalseImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "is_false";
        }

        @Override
        Expression getIfTrue(Type type, final List<Expression> argValueList) {
            return Expressions.constant(false, type);
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return Expressions.equal(argValueList.get(0), FALSE_EXPR);
        }
    }

    /**
     * Implementor for the {@code IS NOT FALSE} SQL operator.
     */
    private static class IsNotFalseImplementor extends AbstractRexCallImplementor {
        IsNotFalseImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "is_not_false";
        }

        @Override
        Expression getIfTrue(Type type, final List<Expression> argValueList) {
            return Expressions.constant(true, type);
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return Expressions.notEqual(argValueList.get(0), FALSE_EXPR);
        }
    }

    /**
     * Implementor for the {@code IS NOT NULL} SQL operator.
     */
    private static class IsNotNullImplementor extends AbstractRexCallImplementor {
        IsNotNullImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "is_not_null";
        }

        @Override
        Expression getIfTrue(Type type, final List<Expression> argValueList) {
            return Expressions.constant(false, type);
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return Expressions.notEqual(argValueList.get(0), NULL_EXPR);
        }
    }

    /**
     * Implementor for the {@code IS NOT TRUE} SQL operator.
     */
    private static class IsNotTrueImplementor extends AbstractRexCallImplementor {
        IsNotTrueImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "is_not_true";
        }

        @Override
        Expression getIfTrue(Type type, final List<Expression> argValueList) {
            return Expressions.constant(true, type);
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return Expressions.notEqual(argValueList.get(0), TRUE_EXPR);
        }
    }

    /**
     * Implementor for the {@code IS NULL} SQL operator.
     */
    private static class IsNullImplementor extends AbstractRexCallImplementor {
        IsNullImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "is_null";
        }

        @Override
        Expression getIfTrue(Type type, final List<Expression> argValueList) {
            return Expressions.constant(true, type);
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return Expressions.equal(argValueList.get(0), NULL_EXPR);
        }
    }

    /**
     * Implementor for the {@code IS TRUE} SQL operator.
     */
    private static class IsTrueImplementor extends AbstractRexCallImplementor {
        IsTrueImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "is_true";
        }

        @Override
        Expression getIfTrue(Type type, final List<Expression> argValueList) {
            return Expressions.constant(false, type);
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return Expressions.equal(argValueList.get(0), TRUE_EXPR);
        }
    }

    /**
     * Implementor for the {@code REGEXP_REPLACE} function.
     */
    private static class RegexpReplaceImplementor extends AbstractRexCallImplementor {
        private final AbstractRexCallImplementor[] implementors = {
                new ReflectiveImplementor(BuiltInMethod.REGEXP_REPLACE3.method, nullPolicy),
                new ReflectiveImplementor(BuiltInMethod.REGEXP_REPLACE4.method, nullPolicy),
                new ReflectiveImplementor(BuiltInMethod.REGEXP_REPLACE5.method, nullPolicy),
                new ReflectiveImplementor(BuiltInMethod.REGEXP_REPLACE6.method, nullPolicy),
        };

        RegexpReplaceImplementor() {
            super(NullPolicy.STRICT, false);
        }

        @Override
        String getVariableName() {
            return "regexp_replace";
        }

        @Override
        Expression implementSafe(MycatRexToLixTranslator translator,
                                 RexCall call, List<Expression> argValueList) {
            return implementors[call.getOperands().size() - 3]
                    .implementSafe(translator, call, argValueList);
        }
    }

    /**
     * Implementor for the {@code DEFAULT} function.
     */
    private static class DefaultImplementor extends AbstractRexCallImplementor {
        DefaultImplementor() {
            super(NullPolicy.NONE, false);
        }

        @Override
        String getVariableName() {
            return "default";
        }

        @Override
        Expression implementSafe(final MycatRexToLixTranslator translator,
                                 final RexCall call, final List<Expression> argValueList) {
            return Expressions.constant(null);
        }
    }


}