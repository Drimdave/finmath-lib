/*
 * (c) Copyright Christian P. Fries, Germany. Contact: email@christian-fries.de.
 *
 * Created on 20.01.2004
 */
package net.finmath.montecarlo.assetderivativevaluation.models;

import java.util.Map;

import net.finmath.montecarlo.RandomVariableFactory;
import net.finmath.montecarlo.RandomVariableFromArrayFactory;
import net.finmath.montecarlo.model.AbstractProcessModel;
import net.finmath.montecarlo.process.MonteCarloProcess;
import net.finmath.stochastic.RandomVariable;
import net.finmath.stochastic.Scalar;

/**
 * This class implements a (variant of the) Bachelier model, that is, it provides the drift and volatility specification
 * and performs the calculation of the numeraire (consistent with the dynamics, i.e. the drift).
 *
 * The model is
 * \[
 * 	dS = r S \mathrm{d}t + \sigma \mathrm{d}W, \quad S(0) = S_{0},
 * \]
 * \[
 * 	dN = r N \mathrm{d}t, \quad N(0) = N_{0},
 * \]
 *
 * <p>
 *
 * Note, the model corresponds to the following dynamic for the numeraire relative stock value \( F(t) = S(t)/N(t) \):
 * \[
 * 	\mathrm{d}F = exp(-r t) \sigma \mathrm{d}W, \quad F(0) = F_{0} = S_{0}/N_{0},
 * \]
 *
 * <p>
 *
 * Note that the variance of \( \int_{t}^{t+\Delta t} \mathrm{d}F \) is
 * \[ \int_{t}^{t+\Delta t} ( exp(-r s) \sigma )^{2} \mathrm{d}s = \frac{\exp(-2 r t)}{2 r} \left( 1 - \exp(-2 r \Delta t) \right) \]
 *
 *
 * The class provides the model of S to an <code>{@link net.finmath.montecarlo.process.MonteCarloProcess}</code> via the specification of
 * \( f = \text{identity} \), \( \mu = \frac{exp(r \Delta t_{i}) - 1}{\Delta t_{i}} S(t_{i}) \), \( \lambda_{1,1} = \sigma \frac{exp(-2 r t_{i}) - exp(-2 r t_{i+1})}{2 r \Delta t_{i}} \), i.e.,
 * of the SDE
 * \[
 * 	dX = \mu dt + \lambda_{1,1} dW, \quad X(0) = \log(S_{0}),
 * \]
 * with \( S = X \). See {@link net.finmath.montecarlo.process.MonteCarloProcess} for the notation.
 *
 * The model's implied Bachelier volatility for a given maturity T is
 * <code>volatility * Math.sqrt((Math.exp(2 * riskFreeRate * optionMaturity) - 1)/(2*riskFreeRate*optionMaturity))</code>
 *
 * @author Christian Fries
 * @see net.finmath.montecarlo.process.MonteCarloProcess The interface for numerical schemes.
 * @see net.finmath.montecarlo.model.ProcessModel The interface for models provinding parameters to numerical schemes.
 * @version 1.0
 */
public class InhomogenousBachelierModel extends AbstractProcessModel {

	private final RandomVariableFactory randomVariableFactory;

	private final RandomVariable initialValue;
	private final RandomVariable riskFreeRate;		// Actually the same as the drift (which is not stochastic)
	private final RandomVariable volatility;

	public InhomogenousBachelierModel(RandomVariableFactory randomVariableFactory, RandomVariable initialValue, RandomVariable riskFreeRate, RandomVariable volatility) {
		super();
		this.randomVariableFactory = randomVariableFactory;
		this.initialValue = initialValue;
		this.riskFreeRate = riskFreeRate;
		this.volatility = volatility;
	}

	/**
	 * Create a Monte-Carlo simulation using given time discretization.
	 *
	 * @param initialValue Spot value.
	 * @param riskFreeRate The risk free rate.
	 * @param volatility The volatility.
	 */
	public InhomogenousBachelierModel(
			final double initialValue,
			final double riskFreeRate,
			final double volatility) {
		super();

		this.randomVariableFactory = new RandomVariableFromArrayFactory();
		this.initialValue	= randomVariableFactory.createRandomVariable(initialValue);
		this.riskFreeRate	= randomVariableFactory.createRandomVariable(riskFreeRate);
		this.volatility		= randomVariableFactory.createRandomVariable(volatility);
	}

	@Override
	public RandomVariable[] getInitialState(MonteCarloProcess process) {
		return new RandomVariable[] { initialValue };
	}

	@Override
	public RandomVariable[] getDrift(final MonteCarloProcess process, final int timeIndex, final RandomVariable[] realizationAtTimeIndex, final RandomVariable[] realizationPredictor) {
		final double dt = process.getTimeDiscretization().getTimeStep(timeIndex);
		final RandomVariable[] drift = new RandomVariable[realizationAtTimeIndex.length];
		for(int componentIndex = 0; componentIndex<realizationAtTimeIndex.length; componentIndex++) {
			drift[componentIndex] = new Scalar(0.0);
		}
		return drift;
	}

	@Override
	public RandomVariable[] getFactorLoading(final MonteCarloProcess process, final int timeIndex, final int component, final RandomVariable[] realizationAtTimeIndex) {
		final double time		= process.getTime(timeIndex);
		final double timeStep	= process.getTimeDiscretization().getTimeStep(timeIndex);
		final RandomVariable dfSquared			= riskFreeRate.mult(-2.0 * time).exp();
		final RandomVariable dfStepSquaredLog	= riskFreeRate.mult(-2.0 * timeStep);
		final RandomVariable dfStepSquared		= dfStepSquaredLog.exp();
		final RandomVariable volatilityEffectiveOverTimeStep = volatility.mult(dfSquared.mult(dfStepSquared.sub(1)).div(dfStepSquaredLog).sqrt());
		return new RandomVariable[] { volatilityEffectiveOverTimeStep };
	}

	@Override
	public RandomVariable applyStateSpaceTransform(final MonteCarloProcess process, final int timeIndex, final int componentIndex, final RandomVariable randomVariable) {
		final double time = process.getTime(timeIndex);
		return randomVariable.mult(riskFreeRate.mult(time).exp());
	}

	@Override
	public RandomVariable applyStateSpaceTransformInverse(final MonteCarloProcess process, final int timeIndex, final int componentIndex, final RandomVariable randomVariable) {
		final double time = process.getTime(timeIndex);
		return randomVariable.div(riskFreeRate.mult(time).exp());
	}

	@Override
	public RandomVariable getNumeraire(final MonteCarloProcess process, final double time) {
		return riskFreeRate.mult(time).exp();
	}

	@Override
	public int getNumberOfComponents() {
		return 1;
	}

	@Override
	public int getNumberOfFactors() {
		return 1;
	}

	@Override
	public RandomVariable getRandomVariableForConstant(final double value) {
		return randomVariableFactory.createRandomVariable(value);
	}

	@Override
	public InhomogenousBachelierModel getCloneWithModifiedData(final Map<String, Object> dataModified) {
		/*
		 * Determine the new model parameters from the provided parameter map.
		 */
		final RandomVariableFactory newRandomVariableFactory = (RandomVariableFactory)dataModified.getOrDefault("randomVariableFactory", randomVariableFactory);

		final RandomVariable newInitialValue	= RandomVariableFactory.getRandomVariableOrDefault(newRandomVariableFactory, dataModified.get("initialValue"), initialValue);
		final RandomVariable newRiskFreeRate	= RandomVariableFactory.getRandomVariableOrDefault(newRandomVariableFactory, dataModified.get("riskFreeRate"), riskFreeRate);
		final RandomVariable newVolatility		= RandomVariableFactory.getRandomVariableOrDefault(newRandomVariableFactory, dataModified.get("volatility"), volatility);

		return new InhomogenousBachelierModel(newRandomVariableFactory, newInitialValue, newRiskFreeRate, newVolatility);
	}

	@Override
	public String toString() {
		return super.toString() + "\n" +
				"InhomogenousBachelierModel:\n" +
				"  initial value...:" + initialValue + "\n" +
				"  risk free rate..:" + riskFreeRate + "\n" +
				"  volatiliy.......:" + volatility;
	}

	/**
	 * Returns the initial value parameter of this model.
	 *
	 * @return Returns the initialValue
	 */
	public RandomVariable getInitialValue() {
		return initialValue;
	}

	/**
	 * Returns the risk free rate parameter of this model.
	 *
	 * @return Returns the riskFreeRate.
	 */
	public RandomVariable getRiskFreeRate() {
		return riskFreeRate;
	}

	/**
	 * Returns the volatility parameter of this model.
	 *
	 * @return Returns the volatility.
	 */
	public RandomVariable getVolatility() {
		return volatility;
	}

	public RandomVariable getImpliedBachelierVolatility(final double maturity) {
		// The Bachelier volatiltiy is the square-root of (the integral from 0 to T of the square of sigma * Math.exp(r t) divided by T)
		final RandomVariable dfStepSquaredLog	= riskFreeRate.mult(-2.0 * maturity);
		final RandomVariable dfStepSquared		= dfStepSquaredLog.exp();
		return volatility.mult(dfStepSquared.sub(1.0).div(dfStepSquaredLog).sqrt());
	}
}
