package org.matsim.rebalancing.run.to_be_deleted;

import ch.sbb.matsim.analysis.skims.*;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.core.utils.misc.Time;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.Set;

import static ch.sbb.matsim.analysis.skims.CalculateSkimMatrices.ZONE_LOCATIONS_FILENAME;

public class SbbSkimMatrices {

    public static void main(String[] args) throws IOException {
        String zonesShapeFilename = "/Users/luchengqi/Documents/MATSimScenarios/Berlin/olympiastadion-study/shp/fan-distribution-v1.shp";
        String zonesIdAttributeName = "Gemeinde_n";
//        String facilitiesFilename = args[2];
        String networkFilename = "/Users/luchengqi/Documents/MATSimScenarios/Berlin/olympiastadion-study/output/test-1000-agents-12pm/output_network.xml.gz";
        String transitScheduleFilename = "/Users/luchengqi/Documents/MATSimScenarios/Berlin/olympiastadion-study/output/test-1000-agents-12pm/output_transitSchedule.xml.gz";
        String eventsFilename = "/Users/luchengqi/Documents/MATSimScenarios/Berlin/olympiastadion-study/output/test-1000-agents-12pm/output_events.xml.gz";
        String outputDirectory = "/Users/luchengqi/Desktop/testing";
        int numberOfPointsPerZone = 5;
        int numberOfThreads = 4;
        String[] timesCarStr = "0;3600;7200".split(";");
        String[] timesPtStr = "0;3600;7200".split(";");
        Set<String> modes = CollectionUtils.stringToSet("car,pt");

        double[] timesCar = new double[timesCarStr.length];
        for (int i = 0; i < timesCarStr.length; i++) {
            timesCar[i] = Time.parseTime(timesCarStr[i]);
        }

        double[] timesPt = new double[timesPtStr.length];
        for (int i = 0; i < timesPtStr.length; i++) {
            timesPt[i] = Time.parseTime(timesPtStr[i]);
        }

        Config config = ConfigUtils.createConfig();
        Random r = new Random(4711);

        CalculateSkimMatrices skims = new CalculateSkimMatrices(outputDirectory, numberOfThreads);

//        skims.calculateSamplingPointsPerZoneFromFacilities(facilitiesFilename, numberOfPointsPerZone, zonesShapeFilename, zonesIdAttributeName, r, f -> 1);
        skims.writeSamplingPointsToFile(new File(outputDirectory, ZONE_LOCATIONS_FILENAME));

        // alternative if you don't have facilities, use the network:
         skims.calculateSamplingPointsPerZoneFromNetwork(networkFilename, numberOfPointsPerZone, zonesShapeFilename, zonesIdAttributeName, r);

        // or load pre-calculated sampling points from an existing file:
        // skims.loadSamplingPointsFromFile("coordinates.csv");

        if (modes.contains(TransportMode.car)) {
            skims.calculateAndWriteNetworkMatrices(networkFilename, eventsFilename, timesCar, config, "", l -> true);
        }

        if (modes.contains(TransportMode.pt)) {
            skims.calculateAndWritePTMatrices(networkFilename,
                    transitScheduleFilename,
                    timesPt[0],
                    timesPt[1],
                    config,
                    "",
                    (line, route) -> route.getTransportMode().equals("train"));
        }

        skims.calculateAndWriteBeelineMatrix();
    }

}
