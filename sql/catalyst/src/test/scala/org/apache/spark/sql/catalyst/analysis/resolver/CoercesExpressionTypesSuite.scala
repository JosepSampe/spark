/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.analysis.resolver

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.SQLConfHelper
import org.apache.spark.sql.catalyst.analysis.{AnsiTypeCoercion, TypeCoercion}
import org.apache.spark.sql.catalyst.expressions.{Add, Cast, Literal}
import org.apache.spark.sql.catalyst.plans.logical.OneRowRelation
import org.apache.spark.sql.types.{DoubleType, IntegerType}

class CoercesExpressionTypesSuite extends SparkFunSuite with SQLConfHelper {

  class TypeCoercer extends CoercesExpressionTypes {
    override protected val ansiTransformations = Seq(AnsiTypeCoercion.ImplicitTypeCasts.transform)
    override protected val nonAnsiTransformations = Seq(TypeCoercion.ImplicitTypeCasts.transform)
  }

  private val integerChild = Literal(1, IntegerType)
  private val doubleChild = Literal(1.1, DoubleType)
  private val castIntegerChild = Cast(child = integerChild, dataType = DoubleType)
  private val typeCoercer = new TypeCoercer

  test("TypeCoercion resolution - with children reinstantiation") {
    val expression = Add(left = doubleChild, right = integerChild)
    val resolvedExpression = typeCoercer
      .coerceExpressionTypes(
        expression = expression,
        expressionTreeTraversal = ExpressionTreeTraversal(
          parentOperator = OneRowRelation(),
          ansiMode = true,
          lcaEnabled = true,
          groupByAliases = true,
          sessionLocalTimeZone = conf.sessionLocalTimeZone
        )
      )
      .asInstanceOf[Add]
    // left child remains the same
    assert(resolvedExpression.left == doubleChild)
    // right first gets resolved to castIntegerChild. However, after the Cast gets
    // re-resolved with timezone, it won't be equal to castIntegerChild because of re-instantiation
    assert(resolvedExpression.right.isInstanceOf[Cast])
    val newRightChild = resolvedExpression.right.asInstanceOf[Cast]
    assert(newRightChild != castIntegerChild)
    assert(newRightChild.timeZoneId.nonEmpty)
    // not a user-specified cast
    assert(newRightChild.getTagValue(Cast.USER_SPECIFIED_CAST).isEmpty)
  }

  test("TypeCoercion resolution - no children reinstantiation") {
    val expression = Add(left = doubleChild, right = castIntegerChild)
    val resolvedExpression = typeCoercer
      .coerceExpressionTypes(
        expression = expression,
        expressionTreeTraversal = ExpressionTreeTraversal(
          parentOperator = OneRowRelation(),
          ansiMode = true,
          lcaEnabled = true,
          groupByAliases = true,
          sessionLocalTimeZone = conf.sessionLocalTimeZone
        )
      )
      .asInstanceOf[Add]
    assert(resolvedExpression.left == doubleChild)
    // Cast that isn't a product of type coercion resolution won't be re-instantiated with timezone
    assert(resolvedExpression.right == castIntegerChild)
  }
}
