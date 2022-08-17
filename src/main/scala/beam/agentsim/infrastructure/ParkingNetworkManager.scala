package beam.agentsim.infrastructure

import akka.actor.{ActorLogging, ActorRef, Cancellable, Props}
import akka.event.Logging
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.LeavingParkingEvent
import beam.agentsim.infrastructure.parking.ParkingNetwork
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.logging.LoggingMessageActor
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.api.core.v01.Id

import scala.concurrent.duration._

class ParkingNetworkManager(beamServices: BeamServices, parkingNetworkMap: ParkingNetwork)
    extends beam.utils.CriticalActor
    with LoggingMessageActor
    with ActorLogging {
  import beamServices._
  private val beamConfig: BeamConfig = beamScenario.beamConfig

  private val counter = {
    val displayPerformanceTimings = beamConfig.beam.agentsim.taz.parkingManager.displayPerformanceTimings
    val logLevel = if (displayPerformanceTimings) Logging.InfoLevel else Logging.DebugLevel
    new SimpleCounter(log, logLevel, "Receiving {} per seconds of ParkingInquiry for {}")
  }

  private val tickTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(2.seconds, 10.seconds, self, "tick")(context.dispatcher)

  override def loggedReceive: Receive = {
    case inquiry: ParkingInquiry if beamConfig.beam.agentsim.taz.parkingManager.method == "PARALLEL" =>
      parkingNetworkMap.processParkingInquiry(inquiry, false, Some(counter)).map(sender() ! _)
    case inquiry: ParkingInquiry =>
      parkingNetworkMap.processParkingInquiry(inquiry).map(sender() ! _)
    case release: ReleaseParkingStall =>
      parkingNetworkMap.processReleaseParkingStall(release)
    case "tick" => counter.tick()
  }

  override def postStop(): Unit = tickTask.cancel()
}

object ParkingNetworkManager extends LazyLogging {

  def props(services: BeamServices, parkingNetworkMap: ParkingNetwork): Props = {
    Props(new ParkingNetworkManager(services, parkingNetworkMap))
  }

  def calculateScore(
    cost: Double,
    energyCharge: Double
  ): Double = -cost - energyCharge

  def handleReleasingParkingSpot(
    tick: Int,
    currentBeamVehicle: BeamVehicle,
    energyChargedMaybe: Option[Double],
    driver: Id[_],
    parkingManager: ActorRef,
    eventsManager: EventsManager,
    triggerId: Long
  ): Unit = {
    val stallForLeavingParkingEvent = currentBeamVehicle.stall match {
      case Some(stall) =>
        parkingManager ! ReleaseParkingStall(stall, triggerId)
        currentBeamVehicle.unsetParkingStall()
        stall
      case None =>
        // This can now happen if a vehicle was charging and released the stall already
        currentBeamVehicle.lastUsedStall.get
    }
    val energyCharge: Double = energyChargedMaybe.getOrElse(0.0)
    val score = calculateScore(stallForLeavingParkingEvent.costInDollars, energyCharge)
    eventsManager.processEvent(
      LeavingParkingEvent(tick, stallForLeavingParkingEvent, score, driver.toString, currentBeamVehicle.id)
    )
  }
}
