package org.matsim.rebalancing.run;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.common.zones.systems.geom_free_zones.GeometryFreeZoneSystem;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import picocli.CommandLine;

import javax.annotation.Nullable;

/***
 * Run DRT simulation with a geometry-free zonal system. When the geometry-free zonal system is to be used,
 * the specially prepared network should be used. (See package drt_zone_generation)
 */
public class RunDrtSimulationsWithGeometryFreeZoneSystem extends MATSimApplication {

	@CommandLine.Option(names = "--new-zone", defaultValue = "false", description = "enable new zonal system")
	private boolean improvedZones;

	public static void main(String[] args) {
		MATSimApplication.run(RunDrtSimulationsWithGeometryFreeZoneSystem.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
		config.addModule(new MultiModeDrtConfigGroup());
		config.addModule(new DvrpConfigGroup());
		MultiModeDrtConfigGroup multiModeDrtConfig = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
		DrtConfigs.adjustMultiModeDrtConfig(multiModeDrtConfig, config.scoring(), config.routing());
		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		scenario.getPopulation()
			.getFactory()
			.getRouteFactories()
			.setRouteFactory(DrtRoute.class, new DrtRouteFactory());
	}

	@Override
	protected void prepareControler(Controler controler) {
		Config config = controler.getConfig();
		MultiModeDrtConfigGroup multiModeDrtConfig = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
		controler.addOverridingModule(new DvrpModule());
		controler.addOverridingModule(new MultiModeDrtModule());
		controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(multiModeDrtConfig));


		if (improvedZones) {
			DrtConfigGroup drtConfigGroup = DrtConfigGroup.getSingleModeDrtConfig(config);
			controler.addOverridingModule(new AbstractDvrpModeModule(drtConfigGroup.mode) {
				@Override
				public void install() {
					bindModal(ZoneSystem.class).toProvider(modalProvider(getter ->
						new GeometryFreeZoneSystem(getter.getModal(Network.class)))).asEagerSingleton();
				}
			});
		}

	}
}
