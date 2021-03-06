package com.firfi.slackbaka.workers

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling._
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.firfi.slackbaka.SlackBaka.{ChatMessage, PrivateResponse}
import reactivemongo.api.bson.collection.BSONCollection
import reactivemongo.api.bson.{BSONDateTime, BSONDocument, BSONDocumentReader}

import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.matching.Regex
import scala.util.{Failure, Random, Success, Try}
import scala.xml.Node

object NomadLoader extends BakaLoader {
  override def getWorkers: Set[Class[_]] = {
    Set(classOf[NomadWorker])
  }
}

object NomadWorker {
  val CITY = "city"
  val COUNTRY = "country"
}

import NomadWorker._

case class MongoGeorecord(user: String, city: String, country: String, lat: Double, lng: Double)

object MongoGeorecord {
  implicit object MongoGeorecordReader extends BSONDocumentReader[MongoGeorecord] {
    override def readDocument(doc: BSONDocument): Try[MongoGeorecord] = {
      def gets(n: String) = doc.getAsTry[String](n)
      def getd(n: String) = doc.getAsTry[Double](n)
      (gets("user"), gets(CITY), gets(COUNTRY), getd("lat"), getd("lng")) match {
        case (Success(user), Success(city), Success(country), Success(lat), Success(lng)) =>
          Success(MongoGeorecord(user, city, country, lat, lng));
        case all => Failure(all.productIterator.toList.map(t => t.asInstanceOf[Try[Any]])
          .filter(t => t.isFailure).head.asInstanceOf[Throwable]);
      }

    }
  }
}

case class Geoname(name: String, countryName: String, lat: Double, lng: Double)

class NomadWorker(responder: ActorRef) extends BakaRespondingWorker(responder) with ScalaXmlSupport with MongoExtension {

  implicit val system = ActorSystem("Nomad")
  implicit val materializer: Materializer = ActorMaterializer()

  val getNomadCitiesCollection = () => getDb().map(db_ => db_[BSONCollection]("nomadCities"))

  lazy val connectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnection("api.geonames.org")
  implicit val geonamesUnmarshaller: FromEntityUnmarshaller[Seq[Geoname]] =
    defaultNodeSeqUnmarshaller
      .map(_ \ "geoname").map(l => l.map((p: Node) => (p \ "name", p \ "countryName", p \ "lat", p \ "lng"))
      .map({
        case (name, countryName, lat, lng) =>
          Geoname(name.text, countryName.text, lat.text.toDouble, lng.text.toDouble)
        })
      )
  val geonamesUsername: String = System.getenv("GEONAMES_USERNAME")

  val commandPrefix = "^?[Bb]aka nomad"

  val setCityPattern: Regex = s"$commandPrefix city set (.+)".r
  val unsetCityPattern: Regex = s"$commandPrefix city unset".r
  val getCityVillagersPatten: Regex = s"$commandPrefix city get (.+)".r
  val listCityPattern: Regex = s"$commandPrefix city list".r
  val setCountryPattern: Regex = s"$commandPrefix country set (.+)".r // and we decline it
  val getCountryPattern: Regex = s"$commandPrefix country get (.+)".r
  val listCountryPattern: Regex = s"$commandPrefix country list".r
  val helpPattern: Regex = s"$commandPrefix help".r

  def migration() = { // data migration example. could be useful
    println("MIGRATION!!!")
    implicit val ord: Ordering[Geoname] = (i: Geoname, j: Geoname) => scala.math.Ordering.String.compare(i.name, j.name)
    (for {
      c <- getNomadCitiesCollection()
      r <- c.find(BSONDocument()).cursor[MongoGeorecord]().collect[List]()
      geonames <- Future.sequence(r.map(mr => getGeoname(CityName(mr.city)).map(r => r.right.get.get))) // migration so we assume data is correct (fixing manually otherwise)
    } yield {
      geonames.distinct.foreach(cityGeoname => {
        println(cityGeoname)
        c.update.one(
          BSONDocument(CITY -> cityGeoname.name),
          BSONDocument("$set" -> BSONDocument(
            "country" -> cityGeoname.countryName,
            "lat" -> cityGeoname.lat,
            "lng" -> cityGeoname.lng
          )),
          multi = true
        ) recoverWith {
          case e: Exception => println(e); Future.successful()
        }
      })
    }) recoverWith {
      case e: Exception => println(e); Future.successful()
    }
  }

  def request(placeName: PlaceName): Future[HttpResponse] = {
    import io.lemonlabs.uri.typesafe.dsl._
    Source.single(RequestBuilding.Get(
      ("/search?" ? ("q" -> placeName.name)
        & ("featureClass" -> placeName.featureClass)
        & ("maxRows" -> 1)
        & ("username" -> geonamesUsername)).toString
    )).via(connectionFlow).runWith(Sink.head)
  }
  def getGeoname(place: PlaceName): Future[Either[String, Option[Geoname]]] = request(place).flatMap {
    case HttpResponse(OK, _, entity, _) => Unmarshal(entity).to[Seq[Geoname]].map(seq => {
      Right(seq.headOption)
    })
    case HttpResponse(status, _, entity, _) => Unmarshal(entity).to[String].map { entity =>
      val error = s"Geonames request failed with status code $status and entity $entity"
      Left(error)
    }
  }
  sealed abstract class PlaceName(val name: String, val typeName: String, val featureClass: String)
  case class CityName(override val name: String) extends PlaceName(name, CITY, "P")
  case class CountryName(override val name: String) extends PlaceName(name, COUNTRY, "A")
  def checkGeoname(place: PlaceName): Future[Either[String, Either[(String, Option[Geoname]), Geoname]]] = getGeoname(place).map { r =>
    def getName(geoname: Geoname, t: PlaceName): String = {
      place match {
        case CityName(_) => geoname.name
        case CountryName(_) => geoname.countryName
      }
    }
    r.right.map {
      case Some(geoname) if getName(geoname, place).toUpperCase == place.name.toUpperCase => Right(geoname)
      case Some(geoname) =>
        Left(
          s"No ${place.typeName} name ${place.name} in database. You meant ${getName(geoname, place)}?"->Some(geoname))
      case None => Left(s"No ${place.typeName} with name ${place.name} found"->None)
    }
  }
  def setNomadCity(cm: ChatMessage, geoname: Geoname): Future[Either[String, Unit]] = {
    getNomadCitiesCollection().flatMap(c =>
      c.update.one(BSONDocument("user" -> cm.user),
        BSONDocument(
          "user" -> cm.user,
          CITY -> geoname.name,
          COUNTRY -> geoname.countryName,
          "lat" -> geoname.lat,
          "lng" -> geoname.lng
        ),
        upsert = true
      ).map(_ => Right()).recover({
        case e =>
          println("Error setting nomad city")
          println(e)
          throw e
      })
    ).recover({
      case e =>
        println("Error getting nomad cities db")
        println(e)
        throw e
    })
  }

  def unsetNomadCity(cm: ChatMessage): Future[Unit] = {
    for {
      c <- getNomadCitiesCollection()
      r <- c.delete.one(BSONDocument("user" -> cm.user))
    } yield {}
  }

  def getNomadPlace(geoname: Geoname, placeName: PlaceName): Future[List[MongoGeorecord]] = {
    import MongoGeorecord._
    for {
      c <- getNomadCitiesCollection()
      r <- c.find(BSONDocument(placeName.typeName -> geoname.name)).cursor[MongoGeorecord]().collect[List]()
    } yield r
  }

  def getAllNomads: Future[List[MongoGeorecord]] = {
    import MongoGeorecord._
    for {
      c <- getNomadCitiesCollection()
      r <- c.find(BSONDocument()).cursor[MongoGeorecord]().collect[List]()
    } yield r
  }

  def checkGeonameThen[T <: PlaceName](placeName: T, f: (Geoname, T, Option[String]) => Future[Either[String, String]]): Future[Either[String, String]] =
    checkGeoname(placeName).flatMap {
      case Right(either) => either match {
        case Left((errorMessageForUser, geoname)) => geoname match {
          case Some(geoname) => f(geoname, placeName, Some(errorMessageForUser))
          case None => Future.successful(Left(errorMessageForUser))
        }
        case Right(geoname) => f(geoname, placeName, None)
      }
      case Left(e) => Future.successful(Left(e))
    }

  def cityEmojiSring(geoname: Geoname): String = if ("Thailand".equals(geoname.countryName)) "" else " :coffin-dance:"

  override def handle(cm: ChatMessage): Future[Either[String, String]] = {
    def nomadsResponse(geoname: Geoname, placeName: PlaceName, warning: Option[String]): Future[Either[String, String]] = {
      getNomadPlace(geoname, placeName).map({
        case nomads@(n :: ns) =>
          responder ! PrivateResponse((List(s"Nomads in ${placeName.typeName} ${geoname.name}:") ::: nomads.sortBy(_.city).map(n => placeName match {
            case CityName(_) => n.user.toSlackMention
            case CountryName(_) => s"${n.user.toSlackMention}: ${n.city}"
          })).mkString("\n"), cm.user)
          Right(s"Nomads in ${placeName.typeName} ${geoname.name} sent to your PM. Nomads count: ${nomads.length}")
        case _ => Right(s"There's no nomads in ${placeName.typeName} ${geoname.name}")
      }).map({
        case Right(msg) if warning.nonEmpty => Right(List(warning.get, msg).mkString("\n\n"))
        case other@_ => other
      }).recoverWith {
        case e: Exception => println(e); Future.successful(Left(e.getMessage))
      }
    }
    def countriesResponse(): Future[Either[String, String]] = {
      getAllNomads.map(nomads =>
        Right(
          (
            List("Nomads per country: ") :::
            nomads.groupBy(n => n.country).toList.sortBy(_._1).map(p => s"${p._1}: ${p._2.length}")
          ).mkString("\n")
        )
      ).recoverWith {
        case e: Exception => println(e); Future.successful(Left(e.getMessage))
      }
    }
    cm.message match {
      case setCityPattern(cityName) => checkGeonameThen(CityName(cityName.trim),
        (geoname, _: CityName, error) => error match {
          case None => setNomadCity(cm, geoname).map(_ => Right(s"City ${geoname.name} set.${cityEmojiSring(geoname)}")).recoverWith {
            case e: Exception => println(e); Future.successful(Left(e.getMessage))
          }
          case Some(msg) => Future.successful(Right(msg))
        }
      )
      case unsetCityPattern() => unsetNomadCity(cm).map(_ => Right(s"Bye mr. nomad"))
      case getCityVillagersPatten(cityName) => checkGeonameThen(CityName(cityName.trim), nomadsResponse)
      case setCountryPattern(_) => Future.successful(Right("No country for old man. Use city command."))
      case getCountryPattern(countryName) => checkGeonameThen(CountryName(countryName.trim), nomadsResponse)
      case listCountryPattern() => countriesResponse()
      case helpPattern() => Future.successful(Right(
        ("Commands: " :: List(helpPattern, setCityPattern, unsetCityPattern, getCityVillagersPatten, getCountryPattern, listCountryPattern).map(_.toString())).mkString("\n")
      ))
     // case "migration" => migration().map(_ => Left())
      case _ => Future { Left("") }
    }
  }
}
