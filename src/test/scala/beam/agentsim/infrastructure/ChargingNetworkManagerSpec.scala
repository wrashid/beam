package beam.agentsim.infrastructure

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, ParkingZoneId, PricingModel}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.integration.Repeated
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServicesImpl}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{DateUtils, StuckFinder, TestConfigUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Retryable
import org.scalatest.wordspec.AnyWordSpecLike

import scala.language.postfixOps

class ChargingNetworkManagerSpec
    extends TestKit(
      ActorSystem(
        "ChargingNetworkManagerSpec",
        ConfigFactory
          .parseString("""
            |akka.log-dead-letters = 10
            |akka.actor.debug.fsm = true
            |akka.loglevel = debug
            |akka.test.timefactor = 2
            |akka.test.single-expect-default = 10 s""".stripMargin)
      )
    )
    with AnyWordSpecLike
    with Matchers
    with BeamHelper
    with ImplicitSender
    with BeforeAndAfterEach
    with Repeated {

  private val filesPath = s"${System.getenv("PWD")}/test/test-resources/beam/input"

  private val conf = system.settings.config
    .withFallback(ConfigFactory.parseString(s"""
         |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath"/vehicleTypes-simple.csv"
         |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath"/vehicles-simple.csv"
         |beam.agentsim.taz.parkingFilePath = "test/input/beamville/parking/taz-parking-limited.csv"
         |beam.router.skim = {
         |  keepKLatestSkims = 1
         |  writeSkimsInterval = 1
         |  writeAggregatedSkimsInterval = 1
         |  taz-skimmer {
         |    name = "taz-skimmer"
         |    fileBaseName = "skimsTAZ"
         |  }
         |}
         |beam.agentsim.chargingNetworkManager {
         |  timeStepInSeconds = 300
         |  scaleUp {
         |    enabled = false
         |  }
         |}
         |""".stripMargin))
    .withFallback(testConfig("test/input/beamville/beam.conf").resolve())

  import ChargingNetworkManager._

  private val beamConfig: BeamConfig = BeamConfig(conf)
  private val matsimConfig = new MatSimBeamConfigBuilder(conf).buildMatSimConf()
  matsimConfig.controler.setOutputDirectory(TestConfigUtils.testOutputDir)
  matsimConfig.controler.setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles)
  private val beamScenario = loadScenario(beamConfig)
  private val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  private val injector = buildInjector(system.settings.config, beamConfig, scenario, beamScenario)
  private val beamServices = new BeamServicesImpl(injector)

  val timeStepInSeconds: Int = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds

  def getBeamVilleCar(
    vehicleId: String,
    parkingStall: ParkingStall,
    fuelToSubtractInPercent: Double = 0.0
  ): BeamVehicle = {
    val beamVilleCar = beamScenario.privateVehicles(Id.create(vehicleId, classOf[BeamVehicle]))
    val fuelToSubtract = beamVilleCar.primaryFuelLevelInJoules * fuelToSubtractInPercent
    beamVilleCar.addFuel(-1 * fuelToSubtract)
    beamVilleCar.connectToChargingPoint(0)
    beamVilleCar.useParkingStall(parkingStall)
    beamVilleCar
  }

  class BeamAgentSchedulerRedirect(
    override val beamConfig: BeamConfig,
    stopTick: Int,
    override val maxWindow: Int,
    override val stuckFinder: StuckFinder
  ) extends BeamAgentScheduler(beamConfig, ".", stopTick, maxWindow, stuckFinder) {

    override def receive: Receive = {
      case Finish => context.stop(self)
      case msg    => testActor ! msg
    }
  }

  private val taz2 = beamServices.beamScenario.tazTreeMap.getTAZ("2").get
  private val chargingPointType = ChargingPointType.CustomChargingPoint("ultrafast", "250.0", "DC")
  private val pricingModel = PricingModel.FlatFee(0.0)

  val personId: Id[Person] = Id.createPersonId("dummyPerson")
  val parkingZoneId: Id[ParkingZoneId] = ParkingZone.createId("0")

  val parkingStall: ParkingStall =
    ParkingStall(
      taz2.tazId,
      parkingZoneId,
      taz2.coord,
      0.0,
      Some(chargingPointType),
      Some(pricingModel),
      ParkingType.Public,
      reservedFor = VehicleManager.AnyManager
    )
  var scheduler: TestActorRef[BeamAgentSchedulerRedirect] = _
  var parkingManager: TestProbe = _
  var personAgent: TestProbe = _
  var chargingNetworkManager: TestActorRef[ChargingNetworkManager] = _

  private val envelopeInUTM = {
    val envelopeInUTM = beamServices.geo.wgs2Utm(beamScenario.transportNetwork.streetLayer.envelope)
    envelopeInUTM.expandBy(beamConfig.beam.spatial.boundingBoxBuffer)
    envelopeInUTM
  }

  "ChargingNetworkManager" should {
    "process trigger PlanningTimeOutTrigger" taggedAs Retryable in {
      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
    }

    "process the last trigger PlanningTimeOutTrigger" taggedAs Retryable in {
      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(DateUtils.getEndOfTime(beamConfig)), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector()
      expectNoMessage()
    }

    "add a vehicle to charging queue with full fuel level but ends up with no fuel added" taggedAs Retryable in {
      val beamVilleCar = getBeamVilleCar("2", parkingStall)
      beamVilleCar.primaryFuelLevelInJoules should be(2.7e8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.7e8)

      chargingNetworkManager ! ChargingPlugRequest(
        10,
        beamVilleCar,
        parkingStall,
        personId,
        0,
        self
      )
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.7e8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      val triggers = expectMsgType[CompletionNotice].newTriggers
      triggers.size shouldBe 2
      triggers(0).trigger shouldBe a[ChargingTimeOutTrigger]
      triggers(0).trigger.asInstanceOf[ChargingTimeOutTrigger].tick shouldBe 300
      triggers(1).trigger shouldBe a[PlanEnergyDispatchTrigger]
      triggers(1).trigger.asInstanceOf[PlanEnergyDispatchTrigger].tick shouldBe 600

      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.7e8)
      beamVilleCar.isConnectedToChargingPoint() should be(true)

      chargingNetworkManager ! ChargingUnplugRequest(610, personId, beamVilleCar, 0)
      expectMsgType[UnpluggingVehicle] shouldBe UnpluggingVehicle(610, personId, beamVilleCar, parkingStall, 0.0)
      beamVilleCar.isConnectedToChargingPoint() should be(false)
    }

    "add a vehicle to charging queue with some fuel required and will charge" taggedAs Retryable in {
      val beamVilleCar = getBeamVilleCar("2", parkingStall, 0.6)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.08e8)

      chargingNetworkManager ! ChargingPlugRequest(
        10,
        beamVilleCar,
        parkingStall,
        personId,
        0,
        self
      )
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.08e8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      val triggers = expectMsgType[CompletionNotice].newTriggers
      triggers.size shouldBe 2
      triggers(0).trigger shouldBe a[ChargingTimeOutTrigger]
      triggers(0).trigger.asInstanceOf[ChargingTimeOutTrigger].tick shouldBe 442
      triggers(1).trigger shouldBe a[PlanEnergyDispatchTrigger]
      triggers(1).trigger.asInstanceOf[PlanEnergyDispatchTrigger].tick shouldBe 600
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.805e8)

      val beamVilleCarCharging = triggers(0).trigger.asInstanceOf[ChargingTimeOutTrigger].chargingVehicle
      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(442, beamVilleCarCharging), 0)
      expectMsgType[EndingRefuelSession] shouldBe EndingRefuelSession(442, beamVilleCar.id, 0)
      expectMsgType[CompletionNotice]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16e8)

      chargingNetworkManager ! ChargingUnplugRequest(500, personId, beamVilleCar, 0)
      expectMsgType[UnpluggingVehicle] shouldBe UnpluggingVehicle(500, personId, beamVilleCar, parkingStall, 1.08e8)
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16e8)
      beamVilleCar.isConnectedToChargingPoint() should be(false)
    }

    "add a vehicle to charging queue with a lot fuel required and will charge in 2 cycles" taggedAs Retryable in {
      val beamVilleCar = getBeamVilleCar("2", parkingStall, 0.5)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35e8)

      chargingNetworkManager ! ChargingPlugRequest(
        10,
        beamVilleCar,
        parkingStall,
        personId,
        0,
        self
      )
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35e8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      val triggers = expectMsgType[CompletionNotice].newTriggers
      triggers.size shouldBe 2
      triggers(0).trigger shouldBe a[ChargingTimeOutTrigger]
      triggers(0).trigger.asInstanceOf[ChargingTimeOutTrigger].tick shouldBe 334
      triggers(1).trigger shouldBe a[PlanEnergyDispatchTrigger]
      triggers(1).trigger.asInstanceOf[PlanEnergyDispatchTrigger].tick shouldBe 600
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.075e8)

      val beamVilleCarCharging = triggers(0).trigger.asInstanceOf[ChargingTimeOutTrigger].chargingVehicle
      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(334, beamVilleCarCharging), 0)
      expectMsgType[EndingRefuelSession] shouldBe EndingRefuelSession(334, beamVilleCar.id, 0)
      expectMsgType[CompletionNotice]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16e8)

      chargingNetworkManager ! ChargingUnplugRequest(700, personId, beamVilleCar, 0)
      expectMsgType[UnpluggingVehicle] shouldBe UnpluggingVehicle(700, personId, beamVilleCar, parkingStall, 8.1e7)
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16e8)
      beamVilleCar.isConnectedToChargingPoint() should be(false)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens before 1st cycle" taggedAs Retryable in {
      val beamVilleCar = getBeamVilleCar("2", parkingStall, 0.5)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35e8)

      chargingNetworkManager ! ChargingPlugRequest(
        10,
        beamVilleCar,
        parkingStall,
        personId,
        0,
        self
      )
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.35e8)

      chargingNetworkManager ! ChargingUnplugRequest(35, personId, beamVilleCar, 0)
      expectMsgType[UnpluggingVehicle] shouldBe UnpluggingVehicle(
        35,
        personId,
        beamVilleCar,
        parkingStall,
        6249999.999999999
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.4125e8) // TODO ???
      beamVilleCar.isConnectedToChargingPoint() should be(false)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.4125e8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 1st cycle" taggedAs Retryable in {
      val beamVilleCar = getBeamVilleCar("2", parkingStall, 0.8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(5.4e7)

      chargingNetworkManager ! ChargingPlugRequest(
        10,
        beamVilleCar,
        parkingStall,
        personId,
        0,
        self
      )
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(5.4e7)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(600), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.265e8)

      chargingNetworkManager ! ChargingUnplugRequest(315, personId, beamVilleCar, 0)
      expectMsgType[UnpluggingVehicle] shouldBe UnpluggingVehicle(315, personId, beamVilleCar, parkingStall, 7.625e7)
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.3025e8) // TODO ???

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.3025e8)
    }

    "add a vehicle to charging queue with a lot fuel required but unplug event happens after 2nd cycle" taggedAs Retryable in {
      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )

      val beamVilleCar = getBeamVilleCar("2", parkingStall, 0.8)
      chargingNetworkManager ! ChargingPlugRequest(
        100,
        beamVilleCar,
        parkingStall,
        personId,
        0,
        self
      )
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(5.4e7)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(600), chargingNetworkManager)
      )
      beamVilleCar.primaryFuelLevelInJoules should be(1.04e8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(600), 0)
      val triggers = expectMsgType[CompletionNotice].newTriggers
      triggers.size shouldBe 2
      triggers(0).trigger shouldBe a[ChargingTimeOutTrigger]
      triggers(0).trigger.asInstanceOf[ChargingTimeOutTrigger].tick shouldBe 748
      triggers(1).trigger shouldBe a[PlanEnergyDispatchTrigger]
      triggers(1).trigger.asInstanceOf[PlanEnergyDispatchTrigger].tick shouldBe 900
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(1.79e8)

      val beamVilleCarCharging = triggers(0).trigger.asInstanceOf[ChargingTimeOutTrigger].chargingVehicle
      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(748, beamVilleCarCharging), 0)
      expectMsgType[EndingRefuelSession] shouldBe EndingRefuelSession(748, beamVilleCar.id, 0)
      expectMsgType[CompletionNotice]
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16e8)

      chargingNetworkManager ! ChargingUnplugRequest(750, personId, beamVilleCar, 0)
      expectMsgType[UnpluggingVehicle] shouldBe UnpluggingVehicle(750, personId, beamVilleCar, parkingStall, 1.62e8)
      expectNoMessage()
      beamVilleCar.primaryFuelLevelInJoules should be(2.16e8)
    }

    "add two vehicles to charging queue and ends both charged" in {
      val beamVilleCar2 = getBeamVilleCar("2", parkingStall, 0.6)
      val beamVilleCar3 = getBeamVilleCar("3", parkingStall, 0.6)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(0), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(300), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(1.08e8)
      beamVilleCar3.primaryFuelLevelInJoules should be(1.08e8)

      chargingNetworkManager ! ChargingPlugRequest(
        10,
        beamVilleCar2,
        parkingStall,
        personId,
        0,
        self
      )
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(1.08e8)

      chargingNetworkManager ! ChargingPlugRequest(
        10,
        beamVilleCar3,
        parkingStall,
        personId,
        112,
        self
      )
      expectMsgType[WaitingToCharge] should be(WaitingToCharge(10, beamVilleCar3.id, parkingStall, 112))
      expectNoMessage()
      beamVilleCar3.primaryFuelLevelInJoules should be(1.08e8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(300), 0)
      val triggers = expectMsgType[CompletionNotice].newTriggers
      triggers.size shouldBe 2
      triggers(0).trigger shouldBe a[ChargingTimeOutTrigger]
      triggers(0).trigger.asInstanceOf[ChargingTimeOutTrigger].tick shouldBe 442
      triggers(1).trigger shouldBe a[PlanEnergyDispatchTrigger]
      triggers(1).trigger.asInstanceOf[PlanEnergyDispatchTrigger].tick shouldBe 600
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(1.805e8)
      beamVilleCar3.primaryFuelLevelInJoules should be(1.08e8)

      val beamVilleCar2Charging = triggers(0).trigger.asInstanceOf[ChargingTimeOutTrigger].chargingVehicle
      chargingNetworkManager ! TriggerWithId(ChargingTimeOutTrigger(442, beamVilleCar2Charging), 12)
      expectMsgType[EndingRefuelSession] shouldBe EndingRefuelSession(442, beamVilleCar2.id, 12)
      expectMsgType[CompletionNotice]
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(2.16e8)
      beamVilleCar2.isConnectedToChargingPoint() should be(true)
      beamVilleCar3.primaryFuelLevelInJoules should be(1.08e8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(600), 0)
      expectMsgType[CompletionNotice].newTriggers shouldBe Vector(
        ScheduleTrigger(PlanEnergyDispatchTrigger(900), chargingNetworkManager)
      )
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(2.16e8)
      beamVilleCar3.primaryFuelLevelInJoules should be(1.08e8)

      chargingNetworkManager ! ChargingUnplugRequest(750, personId, beamVilleCar2, 0)
      expectMsgType[UnpluggingVehicle] shouldBe UnpluggingVehicle(750, personId, beamVilleCar2, parkingStall, 1.08e8)
      expectMsgType[StartingRefuelSession]
      expectNoMessage()
      beamVilleCar2.primaryFuelLevelInJoules should be(2.16e8)
      beamVilleCar3.primaryFuelLevelInJoules should be(1.08e8)

      chargingNetworkManager ! TriggerWithId(PlanEnergyDispatchTrigger(900), 0)
      val triggers2 = expectMsgType[CompletionNotice].newTriggers
      triggers2.size shouldBe 2
      triggers2(0).trigger shouldBe a[ChargingTimeOutTrigger]
      triggers2(0).trigger.asInstanceOf[ChargingTimeOutTrigger].tick shouldBe 1182
      triggers2(1).trigger shouldBe a[PlanEnergyDispatchTrigger]
      triggers2(1).trigger.asInstanceOf[PlanEnergyDispatchTrigger].tick shouldBe 1200
      expectNoMessage()
      beamVilleCar3.primaryFuelLevelInJoules should be(1.455e8)
      beamVilleCar3.isConnectedToChargingPoint() should be(true)
      beamVilleCar2.isConnectedToChargingPoint() should be(false)
    }
  }

  override def beforeEach(): Unit = {
    scheduler = TestActorRef[BeamAgentSchedulerRedirect](
      Props(
        new BeamAgentSchedulerRedirect(
          beamConfig,
          900,
          10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )
    )
    parkingManager = new TestProbe(system)

    import scala.language.existentials
    val (_, chargingNetworkMap, rideHailNetwork) =
      InfrastructureUtils.buildParkingAndChargingNetworks(beamServices, envelopeInUTM)

    chargingNetworkManager = TestActorRef[ChargingNetworkManager](
      ChargingNetworkManager.props(
        beamServices,
        chargingNetworkMap,
        rideHailNetwork,
        parkingManager.ref,
        scheduler
      )
    )
    personAgent = new TestProbe(system)

    chargingNetworkManager ! TriggerWithId(InitializeTrigger(0), 0)
    expectMsgType[ScheduleTrigger] shouldBe ScheduleTrigger(PlanEnergyDispatchTrigger(0), chargingNetworkManager)
    expectMsgType[CompletionNotice]
    expectNoMessage()
  }

  override def afterEach(): Unit = {
    val beamVilleCar = beamScenario.privateVehicles(Id.create(2, classOf[BeamVehicle]))
    beamVilleCar.resetState()
    beamVilleCar.disconnectFromChargingPoint()
    beamVilleCar.unsetParkingStall()
    beamVilleCar.addFuel(beamVilleCar.beamVehicleType.primaryFuelCapacityInJoule)
    scheduler ! Finish
    chargingNetworkManager ! Finish
    parkingManager.ref ! Finish
    personAgent.ref ! Finish
  }

}
