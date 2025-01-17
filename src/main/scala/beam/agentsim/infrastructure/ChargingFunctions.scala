package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType.{Charge, Home, Work}
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{ParkingAlternative, ParkingZoneSearchResult}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.skim.{Skims, SkimsUtils}
import beam.sim.config.BeamConfig
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

class ChargingFunctions(
  geoQuadTree: QuadTree[TAZ],
  idToGeoMapping: scala.collection.Map[Id[TAZ], TAZ],
  parkingZones: Map[Id[ParkingZoneId], ParkingZone],
  distanceFunction: (Coord, Coord) => Double,
  parkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking,
  boundingBox: Envelope,
  seed: Int,
  skims: Option[Skims],
  fuelPrice: Map[FuelType, Double]
) extends ParkingFunctions(
      geoQuadTree,
      idToGeoMapping,
      parkingZones,
      distanceFunction,
      parkingConfig.minSearchRadius,
      parkingConfig.maxSearchRadius,
      parkingConfig.searchMaxDistanceRelativeToEllipseFoci,
      parkingConfig.estimatedMinParkingDurationInSeconds,
      parkingConfig.estimatedMeanEnRouteChargingDurationInSeconds,
      parkingConfig.fractionOfSameTypeZones,
      parkingConfig.minNumberOfSameTypeZones,
      boundingBox,
      seed,
      parkingConfig.multinomialLogit
    ) {

  /**
    * function that verifies if RideHail Then Fast Charging Only
    *
    * @param zone    ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  def ifRideHailCurrentlyOnShiftThenFastChargingOnly(zone: ParkingZone, inquiry: ParkingInquiry): Boolean = {
    zone.chargingPointType.forall(chargingPointType =>
      if (
        inquiry.reservedFor.managerType == VehicleManager.TypeEnum.RideHail || inquiry.beamVehicle
          .exists(v => v.isRideHail && (inquiry.parkingDuration <= 3600 || v.isCAV))
      )
        ChargingPointType.isFastCharger(chargingPointType)
      else true // not a ride hail vehicle seeking charging or parking for two then it is fine to park at slow charger
    )
  }

  /**
    * function that verifies if Charge activity Then Fast Charging Only
    *
    * @param zone    ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  def ifChargeActivityThenFastChargingOnly(zone: ParkingZone, inquiry: ParkingInquiry): Boolean = {
    zone.chargingPointType.forall(chargingPointType =>
      inquiry.parkingActivityType match {
        case Charge => ChargingPointType.isFastCharger(chargingPointType)
        case _      => true // if it is not Charge activity then it does not matter
      }
    )
  }

  /**
    * function that verifies if EnRoute Then Fast Charging Only
    *
    * @param zone    ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  def ifEnrouteThenFastChargingOnly(zone: ParkingZone, inquiry: ParkingInquiry): Boolean = {
    zone.chargingPointType.forall(chargingPointType =>
      inquiry.searchMode match {
        case ParkingSearchMode.EnRouteCharging => ChargingPointType.isFastCharger(chargingPointType)
        case _                                 => true // if it is not EnRoute charging then it does not matter
      }
    )
  }

  /**
    * function that verifies if Home, Work or Overnight Then Slow Charging Only
    *
    * @param zone    ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  def ifHomeWorkOrLongParkingDurationThenSlowChargingOnly(zone: ParkingZone, inquiry: ParkingInquiry): Boolean = {
    zone.chargingPointType.forall(chargingPointType =>
      inquiry.beamVehicle.forall {
        case vehicle
            if !vehicle.isRideHail && (isHomeWorkOrOvernight(inquiry) || hasLongParkingDurationButNotCharge(inquiry)) =>
          !ChargingPointType.isFastCharger(chargingPointType)
        case _ => true
      }
    )
  }

  /**
    * Method that verifies if the vehicle has valid charging capability
    *
    * @param zone             ParkingZone
    * @param beamVehicleMaybe Option[BeamVehicle]
    * @return
    */
  def hasValidChargingCapability(zone: ParkingZone, beamVehicleMaybe: Option[BeamVehicle]): Boolean = {
    zone.chargingPointType.forall(chargingPointType =>
      beamVehicleMaybe.forall(
        _.beamVehicleType.chargingCapability.forall(getPower(_) >= getPower(chargingPointType))
      )
    )
  }

  private def getPower(implicit chargingCapability: ChargingPointType): Double = {
    ChargingPointType.getChargingPointInstalledPowerInKw(chargingCapability)
  }

  private def isHomeWorkOrOvernight(inquiry: ParkingInquiry): Boolean = {
    val isHomeOrWork = List(Home, Work).contains(inquiry.parkingActivityType)
    val isOvernight = inquiry.searchMode == ParkingSearchMode.Init
    isHomeOrWork || isOvernight
  }

  private def hasLongParkingDurationButNotCharge(inquiry: ParkingInquiry): Boolean = {
    inquiry.parkingDuration > 3600.0 && inquiry.searchMode != ParkingSearchMode.EnRouteCharging && inquiry.parkingActivityType != Charge
  }

  /**
    * get Additional Search Filter Predicates
    *
    * @param zone    ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  override protected def setupSearchFilterPredicates(
    zone: ParkingZone,
    inquiry: ParkingInquiry
  ): Boolean = {
    val rideHailFastChargingOnly: Boolean = ifRideHailCurrentlyOnShiftThenFastChargingOnly(zone, inquiry)
    val enRouteFastChargingOnly: Boolean = ifEnrouteThenFastChargingOnly(zone, inquiry)
    val chargeFastChargingOnly: Boolean = ifChargeActivityThenFastChargingOnly(zone, inquiry)
    val overnightStaySlowChargingOnly: Boolean = ifHomeWorkOrLongParkingDurationThenSlowChargingOnly(zone, inquiry)
    val validChargingCapability: Boolean = hasValidChargingCapability(zone, inquiry.beamVehicle)
    val preferredParkingTypes = getPreferredParkingTypes(inquiry)
    val canCarParkHere: Boolean = canThisCarParkHere(zone, inquiry, preferredParkingTypes)
    rideHailFastChargingOnly && validChargingCapability && canCarParkHere && enRouteFastChargingOnly && chargeFastChargingOnly && overnightStaySlowChargingOnly
  }

  /**
    * Update MNL Parameters
    *
    * @param parkingAlternative ParkingAlternative
    * @param inquiry            ParkingInquiry
    * @return
    */
  override protected def setupMNLParameters(
    parkingAlternative: ParkingAlternative,
    inquiry: ParkingInquiry
  ): Map[ParkingMNL.Parameters, Double] = {
    val enrouteFactor: Double = inquiry.searchMode match {
      case ParkingSearchMode.EnRouteCharging =>
        val beamVehicle = inquiry.beamVehicle.get
        val origin = inquiry.originUtm.getOrElse(
          throw new RuntimeException(s"Enroute requires an origin location in parking inquiry $inquiry")
        )
        val travelTime1 = getTravelTime(origin.loc, parkingAlternative.coord, origin.time, beamVehicle.beamVehicleType)
        val travelTime2 = getTravelTime(
          parkingAlternative.coord,
          inquiry.destinationUtm.loc,
          origin.time + travelTime1,
          beamVehicle.beamVehicleType
        )
        ((travelTime1 + travelTime2) / ZonalParkingManager.HourInSeconds) * inquiry.valueOfTime
      case _ => 0.0
    }

    // end-of-day parking durations are set to zero, which will be mis-interpreted here
    val tempParkingDuration = inquiry.searchMode match {
      case ParkingSearchMode.EnRouteCharging => parkingConfig.estimatedMeanEnRouteChargingDurationInSeconds.toInt
      case _                                 => inquiry.parkingDuration.toInt
    }
    val parkingDuration: Option[Int] =
      if (tempParkingDuration < parkingConfig.estimatedMinParkingDurationInSeconds)
        Some(parkingConfig.estimatedMinParkingDurationInSeconds.toInt) // at least a small duration of charging
      else Some(tempParkingDuration)

    val (addedEnergy, _): (Double, Double) =
      inquiry.beamVehicle match {
        case Some(beamVehicle) =>
          parkingAlternative.parkingZone.chargingPointType match {
            case Some(chargingPoint) =>
              val (_, addedEnergy) = ChargingPointType.calculateChargingSessionLengthAndEnergyInJoule(
                chargingPoint,
                beamVehicle.primaryFuelLevelInJoules,
                beamVehicle.beamVehicleType.primaryFuelCapacityInJoule,
                1e6,
                1e6,
                parkingDuration
              )
              val stateOfCharge =
                beamVehicle.primaryFuelLevelInJoules / beamVehicle.beamVehicleType.primaryFuelCapacityInJoule
              (addedEnergy, stateOfCharge)
            case None => (0.0, 0.0) // no charger here
          }
        case None => (0.0, 0.0) // no beamVehicle, assume agent has range
      }

    val rangeAnxietyFactor: Double =
      inquiry.remainingTripData
        .map(_.rangeAnxiety(withAddedFuelInJoules = addedEnergy))
        .getOrElse(0.0) // default no anxiety if no remaining trip data provided

    super[ParkingFunctions].setupMNLParameters(parkingAlternative, inquiry) ++ Map(
      ParkingMNL.Parameters.EnrouteDetourCost -> enrouteFactor,
      ParkingMNL.Parameters.RangeAnxietyCost  -> rangeAnxietyFactor
    )
  }

  /**
    * Generic method that specifies the behavior when MNL returns a ParkingZoneSearchResult
    *
    * @param parkingZoneSearchResult ParkingZoneSearchResult
    */
  override protected def processParkingZoneSearchResult(
    inquiry: ParkingInquiry,
    parkingZoneSearchResult: Option[ParkingZoneSearchResult]
  ): Option[ParkingZoneSearchResult] =
    super[ParkingFunctions].processParkingZoneSearchResult(inquiry, parkingZoneSearchResult)

  /**
    * sample location of a parking stall with a TAZ area
    *
    * @param inquiry     ParkingInquiry
    * @param parkingZone ParkingZone
    * @param taz         TAZ
    * @return
    */
  override protected def sampleParkingStallLocation(
    inquiry: ParkingInquiry,
    parkingZone: ParkingZone,
    taz: TAZ,
    inClosestZone: Boolean = false
  ): Coord = super[ParkingFunctions].sampleParkingStallLocation(inquiry, parkingZone, taz, inClosestZone)

  /**
    * getTravelTime
    *
    * @param origin          Coord
    * @param dest            Coord
    * @param depTime         Integer
    * @param beamVehicleType BeamVehicleType
    * @return
    */
  private def getTravelTime(origin: Coord, dest: Coord, depTime: Int, beamVehicleType: BeamVehicleType): Int = {
    skims map { skim =>
      skim.od_skimmer
        .getTimeDistanceAndCost(
          origin,
          dest,
          depTime,
          BeamMode.CAR,
          beamVehicleType.id,
          beamVehicleType,
          fuelPrice.getOrElse(beamVehicleType.primaryFuelType, 0.0)
        )
        .time
    } getOrElse SkimsUtils.distanceAndTime(BeamMode.CAR, origin, dest)._2
  }

  override protected def getPreferredParkingTypes(inquiry: ParkingInquiry): Set[ParkingType] = {
    import ParkingSearchMode._
    if (parkingConfig.forceParkingType && !List(EnRouteCharging, Init).contains(inquiry.searchMode)) {
      inquiry.parkingActivityType match {
        case Home   => Set(ParkingType.Residential)
        case Work   => Set(ParkingType.Workplace)
        case Charge => Set(ParkingType.Workplace, ParkingType.Public, ParkingType.Residential)
        case _      => Set(ParkingType.Public)
      }
    } else super[ParkingFunctions].getPreferredParkingTypes(inquiry)
  }
}
