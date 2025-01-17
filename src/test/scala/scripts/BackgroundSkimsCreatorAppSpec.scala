package scripts

import akka.actor.ActorSystem
import beam.router.skim.ActivitySimPathType
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.google.inject.Injector
import com.typesafe.config.ConfigFactory
import org.matsim.core.scenario.MutableScenario
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import scripts.BackgroundSkimsCreatorApp.{toCsvSkimRow, InputParameters}

import java.nio.file.Paths

class BackgroundSkimsCreatorAppSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with BeamHelper
    with BeforeAndAfterAll {

  implicit val defaultPatience = PatienceConfig(timeout = Span(30, Seconds))
  val outputPath = Paths.get("output.csv")

  val params = InputParameters(
    configPath = Paths.get("test/input/beamville/beam-with-fullActivitySimBackgroundSkims.conf"),
    input = Some(Paths.get("test/test-resources/beam/router/skim/input.csv")),
    output = outputPath,
    ODSkimsPath = Some(Paths.get("test/test-resources/beam/router/skim/ODSkimsBeamville.csv"))
  )

  val config = ConfigFactory
    .parseString("beam.actorSystemName = \"BackgroundSkimsCreatorAppSpec\"")
    .withFallback(testConfig("test/input/beamville/beam-with-fullActivitySimBackgroundSkims.conf"))
    .resolve()
  val configBuilder = new MatSimBeamConfigBuilder(config)
  val matsimConfig = configBuilder.buildMatSimConf()
  val beamConfig = BeamConfig(config)
  FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
  val (scenarioBuilt, beamScenario, _) = buildBeamServicesAndScenario(beamConfig, matsimConfig)
  val scenario: MutableScenario = scenarioBuilt
  val injector: Injector = buildInjector(config, beamConfig, scenario, beamScenario)
  implicit val actorSystem: ActorSystem = injector.getInstance(classOf[ActorSystem])
  val beamServices: BeamServices = buildBeamServices(injector)

  "BackgroundSkimsCreatorApp" should {

    "run with parameters" in {
      whenReady(BackgroundSkimsCreatorApp.runWithServices(beamServices, params)) { _ =>
        val csv = GenericCsvReader.readAs[ExcerptData](outputPath.toString, toCsvSkimRow, _ => true)._1.toVector
        csv.size shouldBe 11
        csv.count(_.weightedTotalTime > 10) shouldBe 6
      }
    }

    "generate all skims if input is not set" in {
      whenReady(BackgroundSkimsCreatorApp.runWithServices(beamServices, params.copy(input = None))) { _ =>
        val csv = GenericCsvReader.readAs[ExcerptData](outputPath.toString, toCsvSkimRow, _ => true)._1.toVector
        csv.size shouldBe 73
        csv.count(_.weightedTotalTime > 10) shouldBe 33
      }
    }

    "do not generate duplicate values for WALK skims" in {
      whenReady(BackgroundSkimsCreatorApp.runWithServices(beamServices, params.copy(input = None))) { _ =>
        val csv = GenericCsvReader.readAs[ExcerptData](outputPath.toString, toCsvSkimRow, _ => true)._1.toVector
        csv.filter(_.pathType == ActivitySimPathType.WALK).map(_.timePeriodString).distinct shouldBe Vector("EA")
      }
    }
  }

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
  }
}
