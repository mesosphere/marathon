package mesosphere.marathon
package api.akkahttp
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.PathMatcher.{ Matched, Unmatched }
import akka.http.scaladsl.model.{ DateTime, HttpHeader, HttpMethods, HttpProtocols }
import akka.http.scaladsl.server.{ PathMatcher1, Directive0, Directives => AkkaDirectives }
import akka.http.scaladsl.model.headers._
import scala.concurrent.duration._

/**
  * All Marathon Directives and Akka Directives
  *
  * These should be imported by the respective controllers
  */
object Directives extends AuthDirectives with LeaderDirectives with AkkaDirectives {

  /**
    * Path matcher, that matches a segment only, if it is defined in the given set.
    * @param set the allowed path segments.
    */
  class PathIsAvailableInSet(set: Set[String]) extends PathMatcher1[String] {
    def apply(path: Path) = path match {
      case Path.Segment(segment, tail) if set(segment) ⇒ Matched(tail, Tuple1(segment))
      case _ ⇒ Unmatched
    }
  }

  /**
    * Use this directive to enable Cross Origin Resource Sharing for a given set of origins.
    *
    * @param origins the origins to allow.
    */
  def corsResponse(origins: Seq[String]): Directive0 = {
    import HttpMethods._
    extractRequest.flatMap { request =>
      val headers = Seq.newBuilder[HttpHeader]

      // add all pre-defined origins
      origins.foreach(headers += `Access-Control-Allow-Origin`(_))

      // allow all header that are defined as request headers
      request.header[`Access-Control-Request-Headers`].foreach(request =>
        headers += `Access-Control-Allow-Headers`(request.headers)
      )

      // define allowed methods
      headers += `Access-Control-Allow-Methods`(GET, HEAD, OPTIONS)

      // do not ask again for one day
      headers += `Access-Control-Max-Age`(1.day.toSeconds)

      respondWithHeaders (headers.result())
    }
  }

  /**
    * The noCache directive will set proper no-cache headers based on the HTTP protocol
    */
  val noCache: Directive0 = {
    import CacheDirectives._
    import HttpProtocols._
    extractRequest.flatMap {
      _.protocol match {
        case `HTTP/1.0` =>
          respondWithHeaders (
            RawHeader("Pragma", "no-cache")
          )
        case `HTTP/1.1` =>
          respondWithHeaders (
            `Cache-Control`(`no-cache`, `no-store`, `must-revalidate`),
            `Expires`(DateTime.now)
          )
      }
    }
  }
}
