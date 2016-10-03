/*
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.function.glm

import breeze.linalg._
import org.apache.spark.broadcast.Broadcast

import com.linkedin.photon.ml.data.LabeledPoint
import com.linkedin.photon.ml.function._
import com.linkedin.photon.ml.normalization.NormalizationContext
import com.linkedin.photon.ml.optimization.{GLMOptimizationConfiguration, RegularizationType}

/**
 * This class is used to calculate the value, gradient, and Hessian of generalized linear models for individual
 * optimization problems. The loss function of a generalized linear model can all be expressed as:
 *
 * L(w) = \sum_i l(z_i, y_i)
 *
 * with:
 *
 * z_i = w^T^ z_i.
 *
 * Different generalized linear models will have different l(z, y). The functionality of l(z, y) is provided by a
 * [[PointwiseLossFunction]]. Since the loss function could change for different types of normalization, a normalization
 * context object indicates which normalization strategy is used to evaluate the loss function.
 *
 * @param singleLossFunction A single loss function l(z, y) used for the generalized linear model
 */
protected[ml] class IndividualGLMLossFunction private (singleLossFunction: PointwiseLossFunction)
  extends IndividualObjectiveFunction
  with TwiceDiffFunction {

  /**
   * Compute the value of the function over the given data for the given model coefficients.
   *
   * @param input The given data over which to compute the objective value
   * @param coefficients The model coefficients used to compute the function's value
   * @param normalizationContext The normalization context
   * @return The computed value of the function
   */
  override protected[ml] def value(
    input: Iterable[LabeledPoint],
    coefficients: Vector[Double],
    normalizationContext: Broadcast[NormalizationContext]): Double =
    calculate(input, coefficients, normalizationContext)._1

  /**
   * Compute the gradient of the function over the given data for the given model coefficients.
   *
   * @param input The given data over which to compute the gradient
   * @param coefficients The model coefficients used to compute the function's gradient
   * @param normalizationContext The normalization context
   * @return The computed gradient of the function
   */
  override protected[ml] def gradient(
    input: Iterable[LabeledPoint],
    coefficients: Vector[Double],
    normalizationContext: Broadcast[NormalizationContext]): Vector[Double] =
    calculate(input, coefficients, normalizationContext)._2

  /**
   * Compute both the value and the gradient of the function for the given model coefficients (computing value and
   * gradient at once is sometimes more efficient than computing them sequentially).
   *
   * @param input The given data over which to compute the value and gradient
   * @param coefficients The model coefficients used to compute the function's value and gradient
   * @param normalizationContext The normalization context
   * @return The computed value and gradient of the function
   */
  override protected[ml] def calculate(
    input: Iterable[LabeledPoint],
    coefficients: Vector[Double],
    normalizationContext: Broadcast[NormalizationContext]): (Double, Vector[Double]) =
      ValueAndGradientAggregator.calculateValueAndGradient(
        input,
        coefficients,
        singleLossFunction,
        normalizationContext)

  /**
   * Compute the Hessian of the function over the given data for the given model coefficients.
   *
   * @param input The given data over which to compute the Hessian
   * @param coefficients The model coefficients used to compute the function's hessian, multiplied by a given vector
   * @param multiplyVector The given vector to be dot-multiplied with the Hessian. For example, in conjugate
   *                       gradient method this would correspond to the gradient multiplyVector.
   * @param normalizationContext The normalization context
   * @return The computed Hessian multiplied by the given multiplyVector
   */
  override protected[ml] def hessianVector(
    input: Iterable[LabeledPoint],
    coefficients: Vector[Double],
    multiplyVector: Vector[Double],
    normalizationContext: Broadcast[NormalizationContext]): Vector[Double] =
      HessianVectorAggregator.calcHessianVector(
        input,
        coefficients,
        multiplyVector,
        singleLossFunction,
        normalizationContext)

  /**
   * Compute the diagonal of Hessian matrix of the function over the given data for the given model coefficients.
   *
   * @param input The given data over which to compute the diagonal of the Hessian matrix
   * @param coefficients The model coefficients used to compute the diagonal of the Hessian matrix
   * @return The computed diagonal of the Hessian matrix
   */
  override protected[ml] def hessianDiagonal(
    input: Iterable[LabeledPoint],
    coefficients: Vector[Double]) : Vector[Double] =
    HessianDiagonalAggregator.calcHessianDiagonal(input, coefficients, singleLossFunction)
}

object IndividualGLMLossFunction {
  /**
   * Factory method to create new IndividualGLMLossFunctions.
   *
   * @param configuration The optimization problem configuration
   * @param singleLossFunction The PointwiseLossFunction providing functionality for l(z, y)
   * @return A new IndividualGLMLossFunction
   */
  def createLossFunction(
    configuration: GLMOptimizationConfiguration,
    singleLossFunction: PointwiseLossFunction): IndividualGLMLossFunction = {

    val regularizationContext = configuration.regularizationContext

    regularizationContext.regularizationType match {
      case RegularizationType.L2 =>
        val objectiveFunction = new IndividualGLMLossFunction(singleLossFunction) with L2RegularizationTwiceDiff
        objectiveFunction.l2RegularizationWeight = regularizationContext
          .getL2RegularizationWeight(configuration.regularizationWeight)
        objectiveFunction

      case _ => new IndividualGLMLossFunction(singleLossFunction)
    }
  }
}
