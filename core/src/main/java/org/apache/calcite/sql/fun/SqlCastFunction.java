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
package org.apache.calcite.sql.fun;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeFamily;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlMonotonicity;

import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;

import java.text.Collator;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.util.Static.RESOURCE;

/**
 * SqlCastFunction. Note that the std functions are really singleton objects,
 * because they always get fetched via the StdOperatorTable. So you can't store
 * any local info in the class and hence the return type data is maintained in
 * operand[1] through the validation phase.
 *
 * <p>Can be used for both {@link SqlCall} and
 * {@link org.apache.calcite.rex.RexCall}.
 * Note that the {@code SqlCall} has two operands (expression and type),
 * while the {@code RexCall} has one operand (expression) and the type is
 * obtained from {@link org.apache.calcite.rex.RexNode#getType()}.
 *
 * @see SqlCastOperator
 */
public class SqlCastFunction extends SqlFunction {
  //~ Instance fields --------------------------------------------------------

  /** Map of all casts that do not preserve monotonicity. */
  private final SetMultimap<SqlTypeFamily, SqlTypeFamily> nonMonotonicCasts =
      ImmutableSetMultimap.<SqlTypeFamily, SqlTypeFamily>builder()
          .put(SqlTypeFamily.EXACT_NUMERIC, SqlTypeFamily.CHARACTER)
          .put(SqlTypeFamily.NUMERIC, SqlTypeFamily.CHARACTER)
          .put(SqlTypeFamily.APPROXIMATE_NUMERIC, SqlTypeFamily.CHARACTER)
          .put(SqlTypeFamily.DATETIME_INTERVAL, SqlTypeFamily.CHARACTER)
          .put(SqlTypeFamily.CHARACTER, SqlTypeFamily.EXACT_NUMERIC)
          .put(SqlTypeFamily.CHARACTER, SqlTypeFamily.NUMERIC)
          .put(SqlTypeFamily.CHARACTER, SqlTypeFamily.APPROXIMATE_NUMERIC)
          .put(SqlTypeFamily.CHARACTER, SqlTypeFamily.DATETIME_INTERVAL)
          .put(SqlTypeFamily.DATETIME, SqlTypeFamily.TIME)
          .put(SqlTypeFamily.TIMESTAMP, SqlTypeFamily.TIME)
          .put(SqlTypeFamily.TIME, SqlTypeFamily.DATETIME)
          .put(SqlTypeFamily.TIME, SqlTypeFamily.TIMESTAMP)
          .build();

  //~ Constructors -----------------------------------------------------------

  public SqlCastFunction() {
    this(SqlKind.CAST);
  }

  public SqlCastFunction(SqlKind kind) {
    super(kind.toString(), kind, returnTypeInference(kind == SqlKind.SAFE_CAST),
        InferTypes.FIRST_KNOWN, null, SqlFunctionCategory.SYSTEM);
    checkArgument(kind == SqlKind.CAST || kind == SqlKind.SAFE_CAST, kind);
  }

  //~ Methods ----------------------------------------------------------------

  static SqlReturnTypeInference returnTypeInference(boolean safe) {
    return opBinding -> {
      assert opBinding.getOperandCount() == 2;
      final RelDataType ret =
          deriveType(opBinding.getTypeFactory(), opBinding.getOperandType(0),
              opBinding.getOperandType(1), safe);

      if (opBinding instanceof SqlCallBinding) {
        final SqlCallBinding callBinding = (SqlCallBinding) opBinding;
        SqlNode operand0 = callBinding.operand(0);

        // dynamic parameters and null constants need their types assigned
        // to them using the type they are casted to.
        if (SqlUtil.isNullLiteral(operand0, false)
            || operand0 instanceof SqlDynamicParam) {
          callBinding.getValidator().setValidatedNodeType(operand0, ret);
        }
      }
      return ret;
    };
  }

  /** Derives the type of "CAST(expression AS targetType)". */
  public static RelDataType deriveType(RelDataTypeFactory typeFactory,
      RelDataType expressionType, RelDataType targetType, boolean safe) {
    return typeFactory.createTypeWithNullability(targetType,
        expressionType.isNullable() || safe);
  }

  @Override public String getSignatureTemplate(final int operandsCount) {
    assert operandsCount == 2;
    return "{0}({1} AS {2})";
  }

  @Override public SqlOperandCountRange getOperandCountRange() {
    return SqlOperandCountRanges.of(2);
  }

  /**
   * Makes sure that the number and types of arguments are allowable.
   * Operators (such as "ROW" and "AS") which do not check their arguments can
   * override this method.
   */
  @Override public boolean checkOperandTypes(
      SqlCallBinding callBinding,
      boolean throwOnFailure) {
    final SqlNode left = callBinding.operand(0);
    final SqlNode right = callBinding.operand(1);
    if (SqlUtil.isNullLiteral(left, false)
        || left instanceof SqlDynamicParam) {
      return true;
    }
    RelDataType validatedNodeType =
        callBinding.getValidator().getValidatedNodeType(left);
    RelDataType returnType = SqlTypeUtil.deriveType(callBinding, right);
    if (!SqlTypeUtil.canCastFrom(returnType, validatedNodeType, true)) {
      if (throwOnFailure) {
        throw callBinding.newError(
            RESOURCE.cannotCastValue(validatedNodeType.toString(),
                returnType.toString()));
      }
      return false;
    }
    if (SqlTypeUtil.areCharacterSetsMismatched(
        validatedNodeType,
        returnType)) {
      if (throwOnFailure) {
        // Include full type string to indicate character
        // set mismatch.
        throw callBinding.newError(
            RESOURCE.cannotCastValue(validatedNodeType.getFullTypeString(),
                returnType.getFullTypeString()));
      }
      return false;
    }
    return true;
  }

  @Override public SqlSyntax getSyntax() {
    return SqlSyntax.SPECIAL;
  }

  @Override public void unparse(
      SqlWriter writer,
      SqlCall call,
      int leftPrec,
      int rightPrec) {
    assert call.operandCount() == 2;
    final SqlWriter.Frame frame = writer.startFunCall(getName());
    call.operand(0).unparse(writer, 0, 0);
    writer.sep("AS");
    if (call.operand(1) instanceof SqlIntervalQualifier) {
      writer.sep("INTERVAL");
    }
    call.operand(1).unparse(writer, 0, 0);
    writer.endFunCall(frame);
  }

  @Override public SqlMonotonicity getMonotonicity(SqlOperatorBinding call) {
    final RelDataType castFromType = call.getOperandType(0);
    final RelDataTypeFamily castFromFamily = castFromType.getFamily();
    final Collator castFromCollator = castFromType.getCollation() == null
        ? null
        : castFromType.getCollation().getCollator();
    final RelDataType castToType = call.getOperandType(1);
    final RelDataTypeFamily castToFamily = castToType.getFamily();
    final Collator castToCollator = castToType.getCollation() == null
        ? null
        : castToType.getCollation().getCollator();
    if (!Objects.equals(castFromCollator, castToCollator)) {
      // Cast between types compared with different collators: not monotonic.
      return SqlMonotonicity.NOT_MONOTONIC;
    } else if (castFromFamily instanceof SqlTypeFamily
        && castToFamily instanceof SqlTypeFamily
        && nonMonotonicCasts.containsEntry(castFromFamily, castToFamily)) {
      return SqlMonotonicity.NOT_MONOTONIC;
    } else {
      return call.getOperandMonotonicity(0);
    }
  }
}
