package org.matsim.rebalancing.run;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.drt.run.*;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.matsim.application.ApplicationUtils.globFile;

@CommandLine.Command(
	name = "run",
	description = "run drt rebalancing study"
)
public class RunDrtRebalancingSimulations implements MATSimAppCommand {
	@CommandLine.Option(names = "--config", description = "path to config file", required = true)
	private String configPath;

	@CommandLine.Option(names = "--strategy", description = "name of the rebalancing strategy", required = true)
	private String strategyName;

	@CommandLine.Option(names = "--output", description = "root output folder", required = true)
	private String rootOutputDirectory;

	@CommandLine.Option(names = "--fleet-size-from", description = "fleet size (min)", defaultValue = "200")
	private int fleetSizeFrom;

	@CommandLine.Option(names = "--fleet-size-to", description = "fleet size (max)", defaultValue = "800")
	private int fleetSizeTo;

	@CommandLine.Option(names = "--step-size", description = "number of vehicles to add in each run", defaultValue = "50")
	private int stepSize;

	public static void main(String[] args) {
		new RunDrtRebalancingSimulations().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		// create result writer
		if (!Files.exists(Path.of(rootOutputDirectory))) {
			Files.createDirectories(Path.of(rootOutputDirectory));
		}

		CSVPrinter resultsWriter = new CSVPrinter(new FileWriter(rootOutputDirectory + "/result-summary.tsv", true), CSVFormat.TDF);
		resultsWriter.printRecord("fleet_size", "wait_p95", "wait_average", "wait_median", "fleet_distance", "strategy_name");

		for (int fleetSize = fleetSizeFrom; fleetSize <= fleetSizeTo; fleetSize += stepSize) {
			Config config = ConfigUtils.loadConfig(configPath, new MultiModeDrtConfigGroup(), new DvrpConfigGroup());
			String outputDirectory = rootOutputDirectory + "/" + fleetSize;
			config.controller().setOutputDirectory(outputDirectory);

			DrtConfigGroup drtConfigGroup = DrtConfigGroup.getSingleModeDrtConfig(config);
			drtConfigGroup.vehiclesFile = "./drt-vehicles/" + fleetSize + "-8_seater-drt-vehicles.xml";

			Controler controler = DrtControlerCreator.createControler(config, false);
			controler.run();

			performAnalysis(resultsWriter, outputDirectory, fleetSize);
		}

		resultsWriter.close();

		return 0;
	}

	private void performAnalysis(CSVPrinter resultsWriter, String outputDirectory, int fleetSize) throws IOException {

		// read key information from output folders
		Path outputDirectoryPath = Path.of(outputDirectory);
		Path customStats = globFile(outputDirectoryPath, "*drt_customer_stats_drt.csv");
		Path vehStats = globFile(outputDirectoryPath, "*drt_vehicle_stats_drt.csv");
		List<String> outputRow = new ArrayList<>();
		outputRow.add(Integer.toString(fleetSize));

		try (CSVParser parser = new CSVParser(Files.newBufferedReader(customStats),
			CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader())) {

			CSVRecord lastRecord = null;
			for (CSVRecord record : parser) {
				lastRecord = record;
			}

			assert lastRecord != null;
			outputRow.add(lastRecord.get("wait_p95"));
			outputRow.add(lastRecord.get("wait_average"));
			outputRow.add(lastRecord.get("wait_median"));
		}

		try (CSVParser parser = new CSVParser(Files.newBufferedReader(vehStats),
			CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader())) {

			CSVRecord lastRecord = null;
			for (CSVRecord record : parser) {
				lastRecord = record;
			}
			assert lastRecord != null;
			outputRow.add(lastRecord.get("totalDistance"));
		}
		outputRow.add(strategyName);

		resultsWriter.printRecord(outputRow);
	}
}
