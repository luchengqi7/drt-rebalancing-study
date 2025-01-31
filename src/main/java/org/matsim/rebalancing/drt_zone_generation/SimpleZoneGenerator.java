package org.matsim.rebalancing.drt_zone_generation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.common.zones.ZoneImpl;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.common.zones.ZoneSystemUtils;
import org.matsim.contrib.dvrp.router.TimeAsTravelDisutility;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.contrib.zone.skims.SparseMatrix;
import org.matsim.contrib.zone.skims.TravelTimeMatrices;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.rebalancing.utils.ProgressPrinter;

import java.util.*;

/**
 * Generate zonal system for DRT network (i.e., only car mode network). All the links in a zone can be reached within a certain
 * time threshold from the centroid point AND the other way around.
 */
public class SimpleZoneGenerator {
    // (Done) 1. Include # of departures on the link when scoring the links (use input plans)
    // TODO 2. Improve the algorithm, such that as few zone as possible is needed, and the zones can be as similar in size as possible
    // TODO 3. Speed up (e.g. multi-threading?)
    // TODO 4. Consider using node instead of link when determining the centroid -> choose the shortest incoming link to that node as the centroid (?)
    private final Network network;
    private final double timeRadius;

    private final Map<Id<Link>, Set<Id<Link>>> reachableLinksMap = new HashMap<>();
    private final Map<Id<Link>, Double> departuresAndArrivalsScore = new HashMap<>();
    private final Map<Id<Link>, Double> linksScoringMap = new HashMap<>();
    private final SparseMatrix freeSpeedTravelTimeSparseMatrix;

    private final Map<Id<Link>, List<Link>> zonalSystemData = new LinkedHashMap<>();

    private static final Logger log = LogManager.getLogger(SimpleZoneGenerator.class);

    public SimpleZoneGenerator(double timeRadius, Network network, double sparseMatrixMaxDistance) {
        this.timeRadius = timeRadius;
        this.network = network;
        TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
		TravelTimeMatrices.RoutingParams routingParams = new TravelTimeMatrices.RoutingParams(network, travelTime, travelDisutility, Runtime.getRuntime().availableProcessors());
        this.freeSpeedTravelTimeSparseMatrix = TravelTimeMatrices.calculateTravelTimeSparseMatrix(routingParams, sparseMatrixMaxDistance, 0,
                0).get();
    }

    public SimpleZoneGenerator(Network network) {
        this.network = network;
        this.timeRadius = 300;

        TravelTime travelTime = new QSimFreeSpeedTravelTime(1);
        TravelDisutility travelDisutility = new TimeAsTravelDisutility(travelTime);
		TravelTimeMatrices.RoutingParams routingParams = new TravelTimeMatrices.RoutingParams(network, travelTime, travelDisutility, Runtime.getRuntime().availableProcessors());
        this.freeSpeedTravelTimeSparseMatrix = TravelTimeMatrices.calculateTravelTimeSparseMatrix(routingParams, 10000,
                0.0, 0).get();
    }

    public static void main(String[] args) {
        Network inputNetwork = NetworkUtils.readNetwork(args[0]);
        String visualisationNetworkPath = args[1];
        Population inputPlans = args.length == 3 ? PopulationUtils.readPopulation(args[2]) : null;

        SimpleZoneGenerator zoneGenerator = new SimpleZoneGenerator(inputNetwork);
        zoneGenerator.processPlans(inputPlans);
        zoneGenerator.analyzeNetwork();
        zoneGenerator.scoreLinks();
        zoneGenerator.selectCentroids();
        zoneGenerator.removeRedundantCentroid();
        zoneGenerator.createZonesOnNetwork(visualisationNetworkPath);
    }

    public void processPlans(Population inputPlans) {
        network.getLinks().keySet().forEach(linkId -> departuresAndArrivalsScore.put(linkId, 0.));
        if (inputPlans == null) {
            return;
        }
        MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
        for (Person person : inputPlans.getPersons().values()) {
            for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                if (mainModeIdentifier.identifyMainMode(trip.getTripElements()).equals(TransportMode.drt)) {
                    Id<Link> fromLinkId = trip.getOriginActivity().getLinkId();
                    if (fromLinkId == null) {
                        fromLinkId = NetworkUtils.getNearestLink(network, trip.getOriginActivity().getCoord()).getId();
                    }
                    Id<Link> toLinkId = trip.getDestinationActivity().getLinkId();
                    if (toLinkId == null) {
                        toLinkId = NetworkUtils.getNearestLink(network, trip.getDestinationActivity().getCoord()).getId();
                    }
                    double fromLinkNewValue = departuresAndArrivalsScore.get(fromLinkId) + 1;
                    departuresAndArrivalsScore.put(fromLinkId, fromLinkNewValue);
                    double toLinkNewValue = departuresAndArrivalsScore.get(toLinkId) + 0.5;
                    departuresAndArrivalsScore.put(toLinkId, toLinkNewValue);
                }
            }
        }
    }

    public void analyzeNetwork() {
        // Explore reachable links
        log.info("Begin analyzing network. This may take some time...");
        int networkSize = network.getLinks().size();
        ProgressPrinter networkAnalysisCounter = new ProgressPrinter("Network analysis", networkSize);

        network.getLinks().keySet().forEach(linkId -> reachableLinksMap.put(linkId, new HashSet<>()));
        List<Link> linkList = new ArrayList<>(network.getLinks().values());
        for (int i = 0; i < networkSize; i++) {
            Link fromLink = linkList.get(i);

            // Add the itself to the reachable links set
            reachableLinksMap.get(fromLink.getId()).add(fromLink.getId());

            for (int j = i + 1; j < networkSize; j++) {
                Link toLink = linkList.get(j);

                // If the links are too far away (beyond sparse matrix), skip directly
                if (freeSpeedTravelTimeSparseMatrix.get(fromLink.getToNode(), toLink.getFromNode()) == -1) {
                    continue;
                }

                // Check forward direction: from link -> to link
                double forwardTravelTime = calculateVrpLinkToLinkTravelTime(fromLink, toLink);
                if (forwardTravelTime > timeRadius) {
                    continue;
                }

                // Check backward direction: to Link -> from link
                double backwardTravelTime = calculateVrpLinkToLinkTravelTime(toLink, fromLink);
                if (backwardTravelTime > timeRadius) {
                    continue;
                }

                // If we reach here, then the toLink is fully reachable from fromLink.
                reachableLinksMap.get(fromLink.getId()).add(toLink.getId());
                reachableLinksMap.get(toLink.getId()).add(fromLink.getId());

            }
            networkAnalysisCounter.countUp();
        }
    }

    public void scoreLinks() {
        // Calculate score:
        // 1. (If provided) Number of departures and arrivals on reachable links (weighted by time distance)
        // 2. Number of reachable links (weighted by time distance)
        // 3. Length of the link (discourage very long links to be the centroid)
        // 4. (Not yet included) Sum length of the reachable links (weighted by distance)
        log.info("Begin scoring the links");
        for (Id<Link> linkId : network.getLinks().keySet()) {
            double score = 0;
            for (Id<Link> reachableLinkId : reachableLinksMap.get(linkId)) {
                double timeDistance = calculateVrpLinkToLinkTravelTime(network.getLinks().get(linkId), network.getLinks().get(reachableLinkId));
                double departureAndArrivalScore = departuresAndArrivalsScore.get(reachableLinkId);
                double discountFactor = Math.min(1, 200 / network.getLinks().get(linkId).getLength());
                score += (1 + departureAndArrivalScore) * (2 - timeDistance / timeRadius) * discountFactor;
            }
            linksScoringMap.put(linkId, score);
        }

    }

    public void selectCentroids() {
        log.info("Begin choosing centroid of zones");
        List<Id<Link>> rankedPotentialCentroids = new ArrayList<>();
        linksScoringMap.entrySet().
                stream().
                sorted(Map.Entry.comparingByValue(Collections.reverseOrder())).
                forEach(entry -> rankedPotentialCentroids.add(entry.getKey()));
        while (!rankedPotentialCentroids.isEmpty()) {
            Id<Link> chosenLinkId = rankedPotentialCentroids.get(0);
            zonalSystemData.put(chosenLinkId, new ArrayList<>());

            // Remove the links that have been covered
            rankedPotentialCentroids.removeAll(reachableLinksMap.get(chosenLinkId));
        }
        log.info("Centroids generation completed. There are " + zonalSystemData.size() + " centroids (i.e., zones)");
    }

    public void removeRedundantCentroid() {
        log.info("Begin removing redundant centroids");
        // Find all redundant centroids
        Set<Id<Link>> redundantCentroids = identifyRedundantCentroids();
        log.info("Initial number of redundant centroids (i.e., zones) identified = " + redundantCentroids.size());

        // Remove the redundant centroid that covers the minimum number of links
        while (!redundantCentroids.isEmpty()) {
            int minReachableLinks = Integer.MAX_VALUE;
            Id<Link> centroidToRemove = null;
            for (Id<Link> redundantCentroid : redundantCentroids) {
                int numReachableLinks = reachableLinksMap.get(redundantCentroid).size();
                if (numReachableLinks < minReachableLinks) {
                    minReachableLinks = numReachableLinks;
                    centroidToRemove = redundantCentroid;
                }
            }
            zonalSystemData.remove(centroidToRemove);

            // update redundant centroids set
            redundantCentroids = identifyRedundantCentroids();
            log.info("Removing in progress: " + redundantCentroids.size() + " redundant centroids (i.e., zones) left");
        }

        log.info("Removal of redundant centroids complete. There are " + zonalSystemData.size() + " centroids (i.e., zones) remaining");
    }

    protected Set<Id<Link>> identifyRedundantCentroids() {
        Set<Id<Link>> redundantCentroids = new HashSet<>();
        for (Id<Link> centroidLinkId : zonalSystemData.keySet()) {
            Set<Id<Link>> uniqueReachableLinkIds = new HashSet<>(reachableLinksMap.get(centroidLinkId));
            for (Id<Link> anotherCentriodLinkId : zonalSystemData.keySet()) {
                if (centroidLinkId.toString().equals(anotherCentriodLinkId.toString())) {
                    continue;
                }
                uniqueReachableLinkIds.removeAll(reachableLinksMap.get(anotherCentriodLinkId));
            }
            if (uniqueReachableLinkIds.isEmpty()) {
                redundantCentroids.add(centroidLinkId);
            }
        }
        return redundantCentroids;
    }

    public void createZonesOnNetwork(String outputNetwork) {
        // Add links to the zone
        log.info("Assigning links into zones (based on closest centroid)");
        // Record the neighbour for coloring (visualisation)
        Map<String, Set<String>> zoneNeighborsMap = new HashMap<>();
        zonalSystemData.keySet().forEach(linkId -> zoneNeighborsMap.put(linkId.toString(), new HashSet<>()));

        for (Id<Link> linkId : network.getLinks().keySet()) {
            Set<String> relevantZones = new HashSet<>();
            double minDistance = Double.POSITIVE_INFINITY;
            Id<Link> closestCentralLinkId = null;
            for (Id<Link> centroidLinkId : zonalSystemData.keySet()) {
                // If the link is the centroid link, then assign directly and no need to continue
                if (linkId.toString().equals(centroidLinkId.toString())) {
                    closestCentralLinkId = centroidLinkId;
                    break;
                }

                if (reachableLinksMap.get(centroidLinkId).contains(linkId)) {
                    relevantZones.add(centroidLinkId.toString());
                    Link centroidLink = network.getLinks().get(centroidLinkId);
                    Link link = network.getLinks().get(linkId);
                    double distance = calculateVrpLinkToLinkTravelTime(centroidLink, link) + calculateVrpLinkToLinkTravelTime(link, centroidLink);
                    // Forward + backward : this will lead to a better zoning
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestCentralLinkId = centroidLinkId;
                    }
                }
            }
            zonalSystemData.get(closestCentralLinkId).add(network.getLinks().get(linkId));
            if (relevantZones.size() > 1) {
                // if a link is relevant to (i.e., reachable from) more than one zone, then those zones are neighbours
                for (String zoneId : relevantZones) {
                    zoneNeighborsMap.get(zoneId).addAll(relevantZones);
                }
            }
        }

        // Add attribute to the link for visualisation
        log.info("Marking links in each zone");
        // Determine the color of each zone (for visualisation)
        Map<String, Integer> coloringMap = new HashMap<>();
        zoneNeighborsMap.keySet().forEach(zoneId -> coloringMap.put(zoneId, 0));
        for (String zoneId : zoneNeighborsMap.keySet()) {
            Set<Integer> usedColor = new HashSet<>();
            for (String neighboringZone : zoneNeighborsMap.get(zoneId)) {
                usedColor.add(coloringMap.get(neighboringZone));
            }
            boolean colorFound = false;
            int i = 1;
            while (!colorFound) {
                if (usedColor.contains(i)) {
                    i++;
                } else {
                    colorFound = true;
                }
            }
            coloringMap.put(zoneId, i);
        }

        for (Id<Link> centroiLinkId : zonalSystemData.keySet()) {
            int color = coloringMap.get(centroiLinkId.toString());
            for (Link link : zonalSystemData.get(centroiLinkId)) {
                link.getAttributes().putAttribute("zone_color", color);
                link.getAttributes().putAttribute("zone_id", centroiLinkId.toString());
            }
        }

        // Marking centroid links
        for (Id<Link> linkId : network.getLinks().keySet()) {
            if (zonalSystemData.containsKey(linkId)) {
                network.getLinks().get(linkId).getAttributes().putAttribute("isCentroid", "yes");
            } else {
                network.getLinks().get(linkId).getAttributes().putAttribute("isCentroid", "no");
            }
        }
        new NetworkWriter(network).write(outputNetwork);
    }

    protected double calculateVrpLinkToLinkTravelTime(Link fromLink, Link toLink) {
        return freeSpeedTravelTimeSparseMatrix.get(fromLink.getToNode(), toLink.getFromNode()) +
                Math.ceil(toLink.getLength() / toLink.getFreespeed()) + 2;
    }
}
