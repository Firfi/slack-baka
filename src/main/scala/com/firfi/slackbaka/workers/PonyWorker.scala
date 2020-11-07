package com.firfi.slackbaka.workers

import com.firfi.slackbaka.SlackBaka.ChatMessage
import dispatch.{Http, url, Defaults, as}

import akka.actor.ActorRef

import scala.concurrent.Future
import scala.concurrent._
import ExecutionContext.Implicits.global

object PonyLoader extends BakaLoader {
  override def getWorkers:Set[Class[_]] = {
    Set(classOf[PonyWorker])
  }
}

class PonyWorker(responder: ActorRef) extends BakaRespondingWorker(responder) {

  val API_ROOT = "https://derpibooru.org/"

  // comma-separated channek _IDS_
  val ponyAllowedChannels: Set[String] = commaEnvToSet("PONY_ALLOWED_CHANNELS")
  val extraEnvTags: Set[String] = commaEnvToSet("PONY_EXTRA_TAGS")
  val apiKey: Option[String] = Option(System.getenv("PONY_API_KEY"))

  def request(api: String, query: String = ""): Future[String] = {
    val path = api + ".json?" + query
    val svc = url(API_ROOT + path)
    Http.default(svc OK as.String)
  }

  def searchQuery(tags: Seq[String], params: Map[String, String] = Map.empty): String = {
    val COMMA = "%2C"
    val tagsString = tags.map((t) => {t.replace(' ', '+')}).mkString(COMMA)
    (params ++
      Map[String, String](("q", tagsString)) ++
      apiKey.map(k => Map[String, String](("key", k))).getOrElse(Map.empty)).map({case (k, v) => k + "=" + v}).mkString("&")
  }

  private def randomQuery(extraRequestTags: Set[String]): String = {
    searchQuery(
      (extraEnvTags ++ extraRequestTags)
      .foldLeft(Seq.empty[String])({case (acc, tag) =>
        val filtered = if (tag.startsWith("-")) acc.filter(_ != tag.substring(1))
          else acc.filter(_ != s"-$tag")
        if (filtered.length == acc.length) filtered :+ tag else filtered
      }),
      Map("min_score"->"88", "random_image"->"true"))
  }

  val pattern = """(?i).*\bпони\b(.*)""".r
  override def handle(cm: ChatMessage): Future[Either[String, String]] = {
    import scala.util.parsing.json._
    cm.message match {
      case pattern(tagsFollowingPony) if ponyAllowedChannels.contains(cm.channel) =>
        val extraTags = commaSeparatedToSet(tagsFollowingPony).map(encodeURIComponent) // and sanitize dat shit
        request("search", randomQuery(extraTags)).map((res) => {
          // num.zero
          JSON.parseFull(res).get.asInstanceOf[Map[String, Any]]("id").toString.toFloat.toLong // TODO combinators
        }) // TODO json parse errors
        .flatMap((id) => {
          request(id.toString)
        }).map((res) => {
          "https:" + JSON.parseFull(res).get.asInstanceOf[Map[String, Any]]("image").toString
        })
        .map(Right.apply)
      case _ => Future { Left("") }
    }
  }
}
