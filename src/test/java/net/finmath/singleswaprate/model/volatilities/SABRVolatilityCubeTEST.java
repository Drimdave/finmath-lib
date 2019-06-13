package net.finmath.singleswaprate.model.volatilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import net.finmath.marketdata.model.curves.DiscountCurve;
import net.finmath.marketdata.model.curves.ForwardCurve;
import net.finmath.marketdata.model.curves.ForwardCurveFromDiscountCurve;
import net.finmath.marketdata.model.volatilities.SwaptionDataLattice;
import net.finmath.marketdata.model.volatilities.VolatilitySurface.QuotingConvention;
import net.finmath.marketdata.products.Swap;
import net.finmath.randomnumbers.MersenneTwister;
import net.finmath.singleswaprate.Utils;
import net.finmath.singleswaprate.data.DataTable;
import net.finmath.singleswaprate.data.DataTable.TableConvention;
import net.finmath.singleswaprate.data.DataTableInterpolated;
import net.finmath.singleswaprate.data.DataTableLight;
import net.finmath.singleswaprate.data.DataTableLinear;
import net.finmath.singleswaprate.model.AnalyticModelWithVolatilityCubes;
import net.finmath.singleswaprate.model.VolatilityCubeModel;
import net.finmath.time.Schedule;
import net.finmath.time.SchedulePrototype;
import net.finmath.time.businessdaycalendar.BusinessdayCalendar;
import net.finmath.time.businessdaycalendar.BusinessdayCalendarExcludingWeekends;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)

public class SABRVolatilityCubeTEST {

	private static double testAccuracy				= 0.003;
	private static int randomNumberSeed				= 123456;
	private static int randomQueryNumber			= 1000;
	private static int randomQueryTimeLimitInMillis	= 2000;

	private static boolean useLinearInterpolation	= true;

	// files
	private static String curveFilePath					= "./src/test/resources/curves";
	private static String discountCurveFileName			= "EUR-EONIA.crv";
	private static String forwardCurveFileName			= "EUR-OIS6M.crv";
	private static String swaptionFilePath				= "./src/test/resources/swaptions";
	private static String payerFileName					= "CashPayerSwaptionPrice.sdl";
	private static String receiverFileName				= "CashReceiverSwaptionPrice.sdl";
	private static String physicalFileName				= "PhysicalSwaptionPriceATM.sdl";


	// cube parameters
	private static double sabrDisplacement 		= 0.25;
	private static double sabrBeta 				= 0.5;
	private static double sabrRho				= 0.45;
	private static double sabrVolvol			= 0.40;
	private static double correlationDecay 		= 0.045;
	private static double iborOisDecorrelation	= 1.0;

	private static LocalDate referenceDate 		= LocalDate.of(2017, 8, 30);

	private static SchedulePrototype floatMetaSchedule;
	private static SchedulePrototype fixMetaSchedule;

	private static VolatilityCubeModel model;
	private static String forwardCurveName;
	private static SwaptionDataLattice payerSwaptions;
	private static SwaptionDataLattice receiverSwaptions;
	private static SwaptionDataLattice physicalSwaptions;
	private static SwaptionDataLattice physicalVolatilities;

	private VolatilityCube cube;

	private static List<Double> differenceList;
	private static StringBuilder output;
	private static MersenneTwister rng = new MersenneTwister(randomNumberSeed);

	@BeforeClass
	public static void setup() {

		//Get curves
		DiscountCurve discountCurve = null;
		DiscountCurve forwardDiscountCurve = null;
		ForwardCurve forwardCurve = null;
		try (ObjectInputStream discountIn = new ObjectInputStream(new FileInputStream(new File(curveFilePath, discountCurveFileName)));
				ObjectInputStream forwardIn = new ObjectInputStream(new FileInputStream(new File(curveFilePath, forwardCurveFileName)))) {
			discountCurve = (DiscountCurve) discountIn.readObject();
			forwardDiscountCurve = (DiscountCurve) forwardIn.readObject();
		} catch (ClassNotFoundException | IOException e) {
			e.printStackTrace();
		}
		forwardCurve = new ForwardCurveFromDiscountCurve("Forward-" + forwardDiscountCurve.getName(), forwardDiscountCurve.getName(), discountCurve.getName(), referenceDate, "6M",
				new BusinessdayCalendarExcludingWeekends(), BusinessdayCalendar.DateRollConvention.FOLLOWING, 1, 0);

		model = new AnalyticModelWithVolatilityCubes();
		model = (AnalyticModelWithVolatilityCubes) model.addCurve(discountCurve.getName(), discountCurve);
		model = (AnalyticModelWithVolatilityCubes) model.addCurve(forwardCurve.getName(), forwardCurve);
		model = (AnalyticModelWithVolatilityCubes) model.addCurve(forwardDiscountCurve.getName(), forwardDiscountCurve);

		forwardCurveName	= forwardCurve.getName();

		//Get swaption data
		try (ObjectInputStream inPayer = new ObjectInputStream(new FileInputStream(new File(swaptionFilePath, payerFileName)));
				ObjectInputStream inReceiver = new ObjectInputStream(new FileInputStream(new File(swaptionFilePath, receiverFileName)));
				ObjectInputStream inPhysical = new ObjectInputStream(new FileInputStream(new File(swaptionFilePath, physicalFileName)))) {
			payerSwaptions 		= (SwaptionDataLattice) inPayer.readObject();
			receiverSwaptions	= (SwaptionDataLattice) inReceiver.readObject();
			physicalSwaptions	= (SwaptionDataLattice) inPhysical.readObject();
		} catch (ClassNotFoundException | IOException e) {
			e.printStackTrace();
		}

		floatMetaSchedule 	= physicalSwaptions.getFloatMetaSchedule();
		fixMetaSchedule		= physicalSwaptions.getFixMetaSchedule();

		physicalVolatilities = Utils.shiftCashToPhysicalSmile(model, physicalSwaptions, payerSwaptions, receiverSwaptions);

	}

	@Before
	public void clear() {
		cube = makeVolatilityCube();

		differenceList	= new ArrayList<>();
		output			= new StringBuilder();
	}

	@Test
	public void a_cubeATM() {

		System.out.println("Testing cube at atm level...");

		ArrayList<Integer> maturities			= new ArrayList<>();
		ArrayList<Integer> terminations			= new ArrayList<>();
		ArrayList<Double> volatilitiesModel		= new ArrayList<>();
		ArrayList<Double> volatilitiesMarket	= new ArrayList<>();

		for(int maturity : physicalVolatilities.getMaturities(0)) {
			for(int termination : physicalVolatilities.getTenors(0, maturity)) {

				LocalDate maturityDate = referenceDate.plusMonths(maturity);
				LocalDate terminationDate = maturityDate.plusMonths(termination);

				Schedule floatSchedule = floatMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);
				Schedule fixSchedule = fixMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);
				double swapRate = Swap.getForwardSwapRate(fixSchedule, floatSchedule, model.getForwardCurve(forwardCurveName), model);

				try {
					double volatility = cube.getValue(model, fixSchedule.getPayment(fixSchedule.getNumberOfPeriods()-1), fixSchedule.getFixing(0), swapRate,
							QuotingConvention.VOLATILITYNORMAL);
					maturities.add(maturity);
					terminations.add(termination);
					volatilitiesModel.add(volatility);
					volatilitiesMarket.add(physicalVolatilities.getValue(maturity, termination, 0));
				} catch (Exception e) {
					maturities.add(maturity);
					terminations.add(termination);
					volatilitiesModel.add(0.0);
					volatilitiesMarket.add(physicalVolatilities.getValue(maturity, termination, 0));
				}
			}
		}

		DataTableLight modelTable	= new DataTableLight("Volatilites-Model", TableConvention.MONTHS, maturities, terminations, volatilitiesModel);
		DataTableLight marketTable	= new DataTableLight("Volatilites-Market", TableConvention.MONTHS, maturities, terminations, volatilitiesMarket);
		output.append(marketTable.toString()+"\n");
		output.append("\n"+modelTable.toString()+"\n\n\n\n");

	}

	@Test
	public void b_strikeSlices() {

		System.out.println("Testing cube smile nodes...");

		for(int moneyness : physicalVolatilities.getMoneyness()) {

			ArrayList<Integer> marketMaturities = new ArrayList<>();
			ArrayList<Integer> marketTerminations = new ArrayList<>();
			ArrayList<Double> marketVolatilities = new ArrayList<>();

			ArrayList<Integer> modelMaturities = new ArrayList<>();
			ArrayList<Integer> modelTerminations = new ArrayList<>();
			ArrayList<Double> modelVolatilities = new ArrayList<>();

			ArrayList<Double> differenceList = new ArrayList<>();

			for(int maturity : physicalVolatilities.getMaturities(moneyness)) {
				for(int termination : physicalVolatilities.getTenors(moneyness, maturity)) {

					LocalDate maturityDate = referenceDate.plusMonths(maturity);
					LocalDate terminationDate = maturityDate.plusMonths(termination);

					Schedule floatSchedule = floatMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);
					Schedule fixSchedule = fixMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);
					double swapRate = Swap.getForwardSwapRate(fixSchedule, floatSchedule, model.getForwardCurve(forwardCurveName), model);

					double volatilityModel	= 0;
					double volatilityMarket	= 0;
					try {
						volatilityModel = cube.getValue(model, fixSchedule.getPayment(fixSchedule.getNumberOfPeriods()-1), fixSchedule.getFixing(0),
								swapRate+moneyness/10000.0, QuotingConvention.VOLATILITYNORMAL);
						modelMaturities.add(maturity);
						modelTerminations.add(termination);
						modelVolatilities.add(volatilityModel);
					} catch (Exception e) {
						modelMaturities.add(maturity);
						modelTerminations.add(termination);
						modelVolatilities.add(0.0);
					}

					try{
						volatilityMarket = physicalVolatilities.getValue(maturity, termination, moneyness);
						marketMaturities.add(maturity);
						marketTerminations.add(termination);
						marketVolatilities.add(volatilityMarket);
					} catch (Exception e) {}

					if(volatilityModel != 0 && volatilityMarket != 0) {
						differenceList.add(volatilityModel - volatilityMarket);
					}
				}
			}

			DataTable marketTable = new DataTableLight("Volatilities-Market-atMoneyness"+moneyness, TableConvention.MONTHS, marketMaturities, marketTerminations, marketVolatilities);
			DataTable modelTable = new DataTableLight("Volatilites-Model-atMoneyness"+moneyness, TableConvention.MONTHS, modelMaturities, modelTerminations, modelVolatilities);
			output.append(marketTable.toString()+"\n\n");
			output.append(modelTable.toString()+"\n\n");

			double maxDiff	= differenceList.stream().mapToDouble(a -> Math.abs(a)).max().getAsDouble();
			double avrgDiff	= differenceList.stream().mapToDouble(a -> Math.abs(a)).average().getAsDouble();
			output.append("Maximal difference: " + maxDiff + ", Average difference: " + avrgDiff +"\n\n\n\n");
			SABRVolatilityCubeTEST.differenceList.addAll(differenceList);
		}

	}

	@Test
	public void c_testAccessPerformance() {

		System.out.println("Testing cube performance of "+randomQueryNumber+" random accesses...");

		//Determine dimensions to query
		int minMoneyness	= physicalVolatilities.getMoneyness()[0];
		int maxMoneyness	= physicalVolatilities.getMoneyness()[physicalVolatilities.getMoneyness().length-1];
		int minMaturity		= physicalVolatilities.getMaturities()[0];
		int maxMaturity		= physicalVolatilities.getMaturities()[physicalVolatilities.getMaturities().length-1];
		int minTermination	= physicalVolatilities.getTenors()[0];
		int maxTermination	= physicalVolatilities.getTenors()[physicalVolatilities.getTenors().length-1];

		List<double[]> queries = new ArrayList<>();

		while(queries.size() < randomQueryNumber) {
			int moneyness	= randomInt(minMoneyness, maxMoneyness);
			int maturity	= randomInt(minMaturity, maxMaturity);
			int termination	= randomInt(minTermination, maxTermination);
			LocalDate maturityDate		= referenceDate.plusMonths(maturity);
			LocalDate terminationDate	= maturityDate.plusMonths(termination);

			Schedule floatSchedule = floatMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);
			Schedule fixSchedule = fixMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);

			double swapRate = Swap.getForwardSwapRate(fixSchedule, floatSchedule, model.getForwardCurve(forwardCurveName), model);
			double strike	= swapRate + moneyness * 0.0001;

			queries.add(new double[] {floatSchedule.getPayment(floatSchedule.getNumberOfPeriods()-1), floatSchedule.getFixing(0), strike});
		}

		long time = System.currentTimeMillis();
		for(double[] query : queries) {
			cube.getValue(query[0], query[1], query[2], QuotingConvention.VOLATILITYNORMAL);
		}
		time = System.currentTimeMillis() - time;
		System.out.println("Query took " + time + " milliseconds.");
		if(time >= randomQueryTimeLimitInMillis) {
			System.out.println("Outside allowed time frame.");
		}
		Assert.assertTrue(time < randomQueryTimeLimitInMillis);
	}

	@Test
	public void d_testAccessPerformanceOnNodes() {

		System.out.println("Testing cube performance of "+randomQueryNumber+" random accesses on nodes of the tables...");

		int[] moneynesss	= physicalVolatilities.getMoneyness();
		int[] maturities	= physicalVolatilities.getMaturities();
		int[] terminations	= physicalVolatilities.getTenors();

		List<double[]> queries = new ArrayList<>();

		while(queries.size() < randomQueryNumber) {
			int moneyness	= moneynesss[randomInt(0, moneynesss.length-1)];
			int maturity	= maturities[randomInt(0, maturities.length-1)];
			int termination	= terminations[randomInt(0, terminations.length-1)];
			LocalDate maturityDate		= referenceDate.plusMonths(maturity);
			LocalDate terminationDate	= maturityDate.plusMonths(termination);

			Schedule floatSchedule = floatMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);
			Schedule fixSchedule = fixMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);

			double swapRate = Swap.getForwardSwapRate(fixSchedule, floatSchedule, model.getForwardCurve(forwardCurveName), model);
			double strike	= swapRate + moneyness * 0.0001;

			queries.add(new double[] {floatSchedule.getPayment(floatSchedule.getNumberOfPeriods()-1), floatSchedule.getFixing(0), strike});
		}

		long time = System.currentTimeMillis();
		for(double[] query : queries) {
			cube.getValue(query[0], query[1], query[2], QuotingConvention.VOLATILITYNORMAL);
		}
		time = System.currentTimeMillis() - time;
		System.out.println("Query took " + time + " milliseconds.");
		if(time >= randomQueryTimeLimitInMillis) {
			System.out.println("Outside allowed time frame.");
		}
		Assert.assertTrue(time < randomQueryTimeLimitInMillis);
	}

	@After
	public void evaluate() {
		for(double difference : differenceList) {
			Assert.assertEquals(0, difference, testAccuracy);
		}
		System.out.println(output.toString());
	}

	private static int randomInt(int min, int max) {
		int dist	= max - min;
		int random	= (int) Math.round(rng.nextDouble() * dist);
		return random + min;
	}

	private static VolatilityCube makeVolatilityCube() {

		DataTable swapRateTable	= makeSwapRateTable();
		DataTable rhoTable		= makeDummyTable("Rho-Dummy", sabrRho);
		DataTable volvolTable		= makeDummyTable("VolVol-Dummy", sabrVolvol);
		DataTable baseVolTable	= makeBaseVolTable(swapRateTable, rhoTable, volvolTable);

		return new SABRVolatilityCube("TestCube", referenceDate, swapRateTable, sabrDisplacement, sabrBeta, rhoTable, baseVolTable, volvolTable,
				correlationDecay, iborOisDecorrelation);
	}

	private static DataTable makeSwapRateTable() {

		List<Integer> maturitiesList	= new ArrayList<>();
		List<Integer> terminationsList	= new ArrayList<>();
		List<Double>  swapRateList		= new ArrayList<>();

		for(int maturity : physicalVolatilities.getMaturities(0)) {
			for(int termination : physicalVolatilities.getTenors(0, maturity)) {
				maturitiesList.add(maturity);
				terminationsList.add(termination);

				LocalDate maturityDate = referenceDate.plusMonths(maturity);
				LocalDate terminationDate = maturityDate.plusMonths(termination);

				Schedule floatSchedule = floatMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);
				Schedule fixSchedule = fixMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);
				double swapRate = Swap.getForwardSwapRate(fixSchedule, floatSchedule, model.getForwardCurve(forwardCurveName), model);

				swapRateList.add(swapRate);
			}
		}

		if(useLinearInterpolation) {
			return new DataTableLinear("Swap Rates", TableConvention.MONTHS, referenceDate, floatMetaSchedule, maturitiesList, terminationsList,
					swapRateList);
		} else {
			return new DataTableInterpolated("Swap Rates", TableConvention.MONTHS, referenceDate, floatMetaSchedule, maturitiesList, terminationsList,
					swapRateList);
		}
	}

	private static DataTable makeBaseVolTable(DataTable swapRateTable, DataTable rhoTable, DataTable volvolTable) {

		List<Integer> maturitiesList	= new ArrayList<>();
		List<Integer> terminationsList	= new ArrayList<>();
		List<Double>  valuesList		= new ArrayList<>();

		for(int maturity : physicalVolatilities.getMaturities(0)) {
			for(int termination : physicalVolatilities.getTenors(0, maturity)) {
				maturitiesList.add(maturity);
				terminationsList.add(termination);

				valuesList.add(0.01);
			}
		}

		DataTable tempTable;
		if(useLinearInterpolation) {
			tempTable = new DataTableLinear("Temp Volatilities", TableConvention.MONTHS, referenceDate, floatMetaSchedule,
					maturitiesList, terminationsList, valuesList);
		} else {
			tempTable = new DataTableInterpolated("Temp Volatilities", TableConvention.MONTHS, referenceDate, floatMetaSchedule,
					maturitiesList, terminationsList, valuesList);
		}


		VolatilityCube tempCube = new SABRVolatilityCube("tempCube", referenceDate, swapRateTable, sabrDisplacement, sabrBeta, rhoTable, tempTable, volvolTable,
				correlationDecay);

		maturitiesList	= new ArrayList<>();
		terminationsList	= new ArrayList<>();
		valuesList		= new ArrayList<>();
		for(int maturity : physicalVolatilities.getMaturities(0)) {
			for(int termination : physicalVolatilities.getTenors(0, maturity)) {

				LocalDate maturityDate = referenceDate.plusMonths(maturity);
				LocalDate terminationDate = maturityDate.plusMonths(termination);

				Schedule floatSchedule = floatMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);
				Schedule fixSchedule = fixMetaSchedule.generateSchedule(referenceDate, maturityDate, terminationDate);
				double swapRate = Swap.getForwardSwapRate(fixSchedule, floatSchedule, model.getForwardCurve(forwardCurveName), model);

				double matFraction = fixSchedule.getPeriodStart(0);
				double termFraction = fixSchedule.getPeriodEnd(fixSchedule.getNumberOfPeriods()-1);

				maturitiesList.add(maturity);
				terminationsList.add(termination);
				valuesList.add(0.01 * physicalVolatilities.getValue(maturity, termination, 0)
						/ tempCube.getValue(termFraction, matFraction, swapRate, QuotingConvention.VOLATILITYNORMAL));
			}
		}

		if(useLinearInterpolation) {
			return new DataTableLinear("Base Volatilities", TableConvention.MONTHS, referenceDate, floatMetaSchedule, maturitiesList, terminationsList,
					valuesList);
		} else {
			return new DataTableInterpolated("Base Volatilities", TableConvention.MONTHS, referenceDate, floatMetaSchedule, maturitiesList, terminationsList,
					valuesList);
		}
	}

	private static DataTable makeDummyTable(String name, double value) {

		List<Integer> maturitiesList	= new ArrayList<>();
		List<Integer> terminationsList	= new ArrayList<>();
		List<Double>  valuesList		= new ArrayList<>();

		for(int maturity : physicalVolatilities.getMaturities(0)) {
			for(int termination : physicalVolatilities.getTenors(0, maturity)) {
				maturitiesList.add(maturity);
				terminationsList.add(termination);
				valuesList.add(value);
			}
		}

		if(useLinearInterpolation) {
			return new DataTableLinear(name, TableConvention.MONTHS, referenceDate, floatMetaSchedule, maturitiesList, terminationsList, valuesList);
		} else {
			return new DataTableInterpolated(name, TableConvention.MONTHS, referenceDate, floatMetaSchedule, maturitiesList, terminationsList, valuesList);

		}
	}

}
