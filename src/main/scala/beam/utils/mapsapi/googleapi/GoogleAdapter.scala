package beam.utils.mapsapi.googleapi

import java.io.{BufferedOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.{LocalDateTime, ZoneOffset}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.StreamConverters
import akka.util.Timeout
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.mapsapi.Segment
import beam.utils.mapsapi.googleapi.GoogleAdapter._
import org.apache.commons.io.{FileUtils, IOUtils}
import play.api.libs.json.{JsArray, JsLookupResult, JsObject, JsValue, Json}

import scala.util.Try

class GoogleAdapter(apiKey: String, outputResponseToFile: Option[Path] = None, actorSystem: Option[ActorSystem] = None)
    extends AutoCloseable {
  private implicit val system: ActorSystem = actorSystem.getOrElse(ActorSystem())
  private implicit val materializer: ActorMaterializer = ActorMaterializer()
  private implicit val ec = system.dispatcher

  private val fileWriter = outputResponseToFile.map(path => system.actorOf(ResponseSaverActor.props(path.toFile)))

  private val timeout: FiniteDuration = new FiniteDuration(5L, TimeUnit.SECONDS)

  def findRoutes(
    origin: WgsCoordinate,
    destination: WgsCoordinate,
    departureAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    mode: TravelModes.TravelMode = TravelModes.Driving,
    trafficModel: TrafficModels.TrafficModel = TrafficModels.BestGuess,
    constraints: Set[TravelConstraints.TravelConstraint] = Set.empty
  ): Future[Seq[Route]] = {
    val url = buildUrl(apiKey, origin, destination, departureAt, mode, trafficModel, constraints)
    call(url).map(writeToFileIfSetup).map(toRoutes)
  }

  private def call(url: String): Future[JsObject] = {
    val httpRequest = HttpRequest(uri = url)
    val responseFuture: Future[HttpResponse] = Http().singleRequest(httpRequest)
    responseFuture.map { response =>
      val inputStream = response.entity.dataBytes.runWith(StreamConverters.asInputStream(timeout))
      Json.parse(inputStream).as[JsObject]
    }
  }

  private def parseRoutes(jsRoutes: Seq[JsValue]): Seq[Route] = {
    jsRoutes.map { route =>
      val firstAndUniqueLeg = (route \ "legs").as[JsArray].value.head
      parseRoute(firstAndUniqueLeg.as[JsObject])
    }
  }

  private def writeToFileIfSetup(jsObject: JsObject): JsObject = {
    fileWriter.foreach(_ ! jsObject)
    jsObject
  }

  private def toRoutes(jsObject: JsObject): Seq[Route] = {

    parseRoutes((jsObject \ "routes").as[JsArray].value)
  }

  private def parseRoute(jsObject: JsObject): Route = {
    val segments = parseSegments((jsObject \ "steps").as[JsArray].value)
    val distanceInMeter = (jsObject \ "distance" \ "value").as[Int]
    val durationInSeconds = (jsObject \ "duration" \ "value").as[Int]
    val startLocation = parseWgsCoordinate(jsObject \ "start_location")
    val endLocation = parseWgsCoordinate(jsObject \ "end_location")
    Route(startLocation, endLocation, distanceInMeter, durationInSeconds, segments)
  }

  private def parseStep(jsObject: JsObject): Segment = {
    Segment(
      coordinates = GooglePolylineDecoder.decode((jsObject \ "polyline" \ "points").as[String]),
      lengthInMeters = (jsObject \ "distance" \ "value").as[Int],
      durationInSeconds = Some((jsObject \ "duration" \ "value").as[Int])
    )
  }

  private def parseSegments(steps: Seq[JsValue]): Seq[Segment] = {
    steps.map { step =>
      parseStep(step.as[JsObject])
    }
  }

  private def parseWgsCoordinate(position: JsLookupResult) = {
    WgsCoordinate(
      latitude = (position \ "lat").as[Double],
      longitude = (position \ "lng").as[Double]
    )
  }

  override def close(): Unit = {
    implicit val timeOut = new Timeout(20L, TimeUnit.SECONDS)
    fileWriter.foreach { ref =>
      val closed = ref ? ResponseSaverActor.CloseMsg
      Try(Await.result(closed, timeOut.duration))
      ref ! PoisonPill
    }
    Http().shutdownAllConnectionPools
      .andThen {
        case _ =>
          if (!materializer.isShutdown) materializer.shutdown()
          if (actorSystem.isEmpty) system.terminate()
      }
  }

}

object GoogleAdapter {

  private[googleapi] def buildUrl(
    apiKey: String,
    origin: WgsCoordinate,
    destination: WgsCoordinate,
    departureAt: LocalDateTime,
    mode: TravelModes.TravelMode,
    trafficModel: TrafficModels.TrafficModel = TrafficModels.BestGuess,
    constraints: Set[TravelConstraints.TravelConstraint]
  ): String = {
    // avoid=tolls|highways|ferries
    val originStr = s"${origin.latitude},${origin.longitude}"
    val destinationStr = s"${destination.latitude},${destination.longitude}"
    val params = Seq(
      s"mode=${mode.apiString}",
      "language=en",
      "units=metric",
      "alternatives=true",
      s"key=$apiKey",
      s"mode=${mode.apiString}",
      s"origin=$originStr",
      s"destination=$destinationStr",
      s"traffic_model=${trafficModel.apiString}",
      s"departure_time=${dateAsEpochSecond(departureAt)}",
    )
    val optionalParams = {
      if (constraints.isEmpty) Seq.empty
      else Seq(s"avoid=${constraints.map(_.apiName).mkString("|")}")
    }

    val baseUrl = "https://maps.googleapis.com/maps/api/directions/json"

    s"$baseUrl${(params ++ optionalParams).mkString("?", "&", "")}"
  }

  private def dateAsEpochSecond(ldt: LocalDateTime): Long = {
    ldt.toEpochSecond(ZoneOffset.UTC)
  }

}

class ResponseSaverActor(file: File) extends Actor {
  override def receive = {
    case jsObject: JsObject =>
      val out = FileUtils.openOutputStream(file)
      val buffer = new BufferedOutputStream(out)
      IOUtils.write("[\n", buffer, StandardCharsets.UTF_8)
      IOUtils.write(Json.prettyPrint(jsObject), buffer, StandardCharsets.UTF_8)
      context.become(saveIncoming(buffer))
    case ResponseSaverActor.CloseMsg =>
      sender() ! ResponseSaverActor.ClosedRsp
  }

  def saveIncoming(buffer: BufferedOutputStream): Actor.Receive = {
    case jsObject: JsObject =>
      IOUtils.write(",\n", buffer, StandardCharsets.UTF_8)
      IOUtils.write(Json.prettyPrint(jsObject), buffer, StandardCharsets.UTF_8)
    case ResponseSaverActor.CloseMsg =>
      IOUtils.write("\n]", buffer, StandardCharsets.UTF_8)
      buffer.close()
      sender() ! ResponseSaverActor.ClosedRsp
  }

}

object ResponseSaverActor {
  object CloseMsg
  object ClosedRsp

  def props(file: File): Props = {
    Props(new ResponseSaverActor(file))
  }
}
