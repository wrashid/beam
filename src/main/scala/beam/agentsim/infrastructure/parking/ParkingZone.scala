package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.power.SitePowerManager
import beam.agentsim.infrastructure.taz.TAZ
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

import scala.language.higherKinds

trait ParkingZoneId

/**
  * stores the number of stalls in use for a zone of parking stalls with a common set of attributes
  *
  * @param parkingZoneId the Id of this Zone, which directly corresponds to the Array index of this used in the ParkingZoneSearch Array[ParkingZone]
  * @param stallsAvailable a (mutable) count of stalls free, which is mutated to track the current state of stalls in a way that is logically similar to a semiphore
  * @param maxStalls the maximum number of stalls which can be in use at this ParkingZone
  * @param chargingPointType if this stall has charging, this is the type of charging
  * @param pricingModel if this stall has pricing, this is the type of pricing
  */
class ParkingZone(
  val parkingZoneId: Id[ParkingZoneId],
  val tazId: Id[TAZ],
  val parkingType: ParkingType,
  var stallsAvailable: Int,
  val maxStalls: Int,
  val reservedFor: ReservedFor,
  val chargingPointType: Option[ChargingPointType],
  val pricingModel: Option[PricingModel],
  val timeRestrictions: Map[VehicleCategory, Range],
  val link: Option[Link],
  val siteId: Id[SitePowerManager]
) {

  /**
    * the percentage of parking available in this ParkingZone
    *
    * @return percentage [0.0, 1.0]
    */
  def availability: Double = if (maxStalls == 0) 0.0 else stallsAvailable.toDouble / maxStalls

  override def toString: String = {
    val chargeString = chargingPointType match {
      case None    => "chargingPointType = None"
      case Some(c) => s" chargingPointType = $c"
    }
    val pricingString = pricingModel match {
      case None    => "pricingModel = None"
      case Some(p) => s" pricingModel = $p"
    }
    s"ParkingZone(parkingZoneId = $parkingZoneId, numStalls = $stallsAvailable, $chargeString, $pricingString)"
  }

  override def equals(that: Any): Boolean =
    that match {
      case that: ParkingZone => that.hashCode() == hashCode
      case _                 => false
    }
  override def hashCode: Int = parkingZoneId.hashCode()
}

object ParkingZone extends LazyLogging {

  val DefaultParkingZoneId: Id[ParkingZoneId] = Id.create("default", classOf[ParkingZoneId])

  // used in place of Int.MaxValue to avoid possible buffer overrun due to async failures
  // in other words, while stallsAvailable of a ParkingZone should never exceed the numStalls
  // it started with, it could be possible in the system to happen due to scheduler issues. if
  // it does, it would be more helpful for it to reflect with a reasonable number, ie., 1000001,
  // which would tell us that we had 1 extra releaseStall event.
  val UbiqiutousParkingAvailability: Int = 1000000

  /**
    * creates a new StallValues object
    *
    * @param chargingPointType if this stall has charging, this is the type of charging
    * @param pricingModel if this stall has pricing, this is the type of pricing
    * @return a new StallValues object
    */
  private def apply(
    parkingZoneId: Id[ParkingZoneId],
    geoId: Id[TAZ],
    parkingType: ParkingType,
    reservedFor: ReservedFor,
    siteId: Id[SitePowerManager],
    stallsAvailable: Int = 0,
    maxStalls: Int = 0,
    chargingPointType: Option[ChargingPointType] = None,
    pricingModel: Option[PricingModel] = None,
    timeRestrictions: Map[VehicleCategory, Range] = Map.empty,
    link: Option[Link] = None
  ): ParkingZone =
    new ParkingZone(
      parkingZoneId,
      geoId,
      parkingType,
      stallsAvailable,
      maxStalls,
      reservedFor,
      chargingPointType,
      pricingModel,
      timeRestrictions,
      link,
      siteId
    )

  def defaultInit(
    geoId: Id[TAZ],
    parkingType: ParkingType,
    numStalls: Int
  ): ParkingZone = {
    init(
      Some(DefaultParkingZoneId),
      geoId,
      parkingType,
      VehicleManager.AnyManager,
      Some(SitePowerManager.createId(DefaultParkingZoneId.toString)),
      numStalls
    )
  }

  def init(
    parkingZoneIdMaybe: Option[Id[ParkingZoneId]],
    geoId: Id[TAZ],
    parkingType: ParkingType,
    reservedFor: ReservedFor,
    siteIdMaybe: Option[Id[SitePowerManager]],
    maxStalls: Int = 0,
    chargingPointType: Option[ChargingPointType] = None,
    pricingModel: Option[PricingModel] = None,
    timeRestrictions: Map[VehicleCategory, Range] = Map.empty,
    link: Option[Link] = None
  ): ParkingZone = {
    val parkingZoneId = parkingZoneIdMaybe match {
      case Some(parkingZoneId) => parkingZoneId
      case _                   => constructParkingZoneKey(reservedFor, geoId, parkingType, chargingPointType, pricingModel, maxStalls)
    }
    val siteId = siteIdMaybe match {
      case Some(siteId) => siteId
      case _            => SitePowerManager.constructSitePowerKey(reservedFor, geoId, parkingType, chargingPointType)
    }
    ParkingZone(
      parkingZoneId,
      geoId,
      parkingType,
      reservedFor,
      siteId,
      maxStalls,
      maxStalls,
      chargingPointType,
      pricingModel,
      timeRestrictions,
      link
    )
  }

  /**
    * increment the count of stalls in use
    *
    * @param parkingZone the object to increment
    * @return True|False (representing success) wrapped in an effect type
    */
  def releaseStall(parkingZone: ParkingZone): Boolean =
    if (parkingZone.parkingZoneId == DefaultParkingZoneId) {
      // this zone does not exist in memory but it has infinitely many stalls to release
      true
    } else if (parkingZone.stallsAvailable + 1 > parkingZone.maxStalls) {
//        log.debug(s"Attempting to release a parking stall when ParkingZone is already full.")
      false
    } else {
      parkingZone.stallsAvailable += 1
      true
    }

  /**
    * decrement the count of stalls in use. doesn't allow negative-values (fails silently)
    *
    * @param parkingZone the object to increment
    * @return True|False (representing success) wrapped in an effect type
    */
  def claimStall(parkingZone: ParkingZone): Boolean =
    if (parkingZone.parkingZoneId == DefaultParkingZoneId) {
      // this zone does not exist in memory but it has infinitely many stalls to release
      true
    } else if (parkingZone.stallsAvailable - 1 >= 0) {
      parkingZone.stallsAvailable -= 1
      true
    } else {
      // log debug that we tried to claim a stall when there were no free stalls
      false
    }

  /**
    * Option-wrapped Array index lookup for Array[ParkingZone]
    *
    * @param parkingZones collection of parking zones
    * @param parkingZoneId an array index
    * @return Optional ParkingZone
    */
  def getParkingZone(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone],
    parkingZoneId: Id[ParkingZoneId]
  ): Option[ParkingZone] = {
    val result = parkingZones.get(parkingZoneId)
    if (result.isEmpty) {
      logger.warn(s"attempting to access parking zone with illegal parkingZoneId $parkingZoneId, will be ignored")
    }
    result
  }

  /**
    * construct ID of a Parking Zone
    * @param geoId TAZ ID
    * @param parkingType Parking Type
    * @param chargingPointTypeMaybe Charging Point Type Option
    * @param pricingModelMaybe Pricing Model Option
    * @param numStalls number of stalls
    * @return
    */
  def constructParkingZoneKey(
    reservedFor: ReservedFor,
    geoId: Id[_],
    parkingType: ParkingType,
    chargingPointTypeMaybe: Option[ChargingPointType],
    pricingModelMaybe: Option[PricingModel],
    numStalls: Int
  ): Id[ParkingZoneId] = {
    val chargingPointType = chargingPointTypeMaybe.getOrElse("NoCharger")
    val pricingModel = pricingModelMaybe.getOrElse("Free")
    val costInCents = pricingModelMaybe.map(x => (x.costInDollars * 100).toInt).getOrElse(0)
    createId(
      s"zone-${reservedFor}-${geoId}-${parkingType}-${chargingPointType}-${pricingModel}-${costInCents}-$numStalls"
    )
  }

  /**
    * create Id
    * @param zoneId the zone Id
    * @return
    */
  def createId(zoneId: String): Id[ParkingZoneId] = Id.create(zoneId, classOf[ParkingZoneId])
}
