package org.matsim.rebalancing.run.to_be_deleted;

import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;

public class TestRun {
	public static void main(String[] args) {
		Population population = PopulationUtils.readPopulation("/Users/luchengqi/Documents/MATSimScenarios/drt-scenario-library/drt-open-scenarios/vulkaneifel/vulkaneifel-25pct-trips.plans.xml.gz");
		System.out.println("Population size = " + population.getPersons().size());
	}
}
