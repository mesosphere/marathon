package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ DateTime, HttpHeader, HttpMethods, HttpProtocols }
import akka.http.scaladsl.server.PathMatcher.{ Matched, Matching, Unmatched }
import akka.http.scaladsl.server.directives.BasicDirectives.{ extract, mapRequestContext, tprovide }
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.http.scaladsl.server.{ Directive, Directive0, Directive1, PathMatcher, PathMatcher1, Rejection, Directives => AkkaDirectives }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ Group, PathId, RootGroup, Timestamp }

import scala.annotation.tailrec
import scala.concurrent.duration._

/**
  * All Marathon Directives and Akka Directives
  *
  * These should be imported by the respective controllers
  */
object Directives extends AuthDirectives with LeaderDirectives with AkkaDirectives {
  /**
    * Matches the rest of the path segment as a PathId; ignores trailing slash, consumes everything.
    */
  object RemainingPathId extends PathMatcher1[PathId] {
    import akka.http.scaladsl.server.PathMatcher._

    @tailrec final def iter(reversePieces: List[String], remaining: Path): Matching[Tuple1[PathId]] = remaining match {
      case Path.Slash(rest) =>
        iter(reversePieces, rest)
      case Path.Empty =>
        if (reversePieces.nonEmpty)
          Matched(Path.Empty, Tuple1(PathId.sanitized(reversePieces.reverse, true)))
        else
          Unmatched
      case Path.Segment(segment, rest) =>
        iter(segment :: reversePieces, rest)
    }

    override def apply(path: Path) = iter(Nil, path)
  }

  /**
    * Given the current root group, only match and consume an existing appId
    *
    * This is useful because our v2 API has an unfortunate design decision which leads to ambiguity in our URLs, such as:
    *
    *   POST /v2/apps/my-group/restart/restart
    *
    * The intention here is to restart the app named "my-group/restart"
    *
    * This matcher will only consume "my-group/restart" from the path, leaving the rest of the matcher to match the rest
    */
  case class ExistingAppPathId(rootGroup: RootGroup) extends PathMatcher1[PathId] {
    import akka.http.scaladsl.server.PathMatcher._

    @tailrec final def iter(reversePieces: List[String], remaining: Path, group: Group): Matching[Tuple1[PathId]] = remaining match {
      case Path.Slash(rest) =>
        iter(reversePieces, rest, group)
      case Path.Segment(segment, rest) =>
        val appended = segment :: reversePieces
        val pathId = PathId.sanitized(appended.reverse, true)
        if (group.groupsById.contains(pathId)) {
          iter(appended, rest, group.groupsById(pathId))
        } else if (group.apps.contains(pathId)) {
          Matched(rest, Tuple1(pathId))
        } else {
          Unmatched
        }
      case _ =>
        Unmatched
    }

    override def apply(path: Path) = iter(Nil, path, rootGroup)
  }

  final val RemainingTaskId = Remaining.map(s => Task.Id(s))

  final val Version = Segment.map(Timestamp(_))

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

  private val marathonApiKeywords = Set("restart", "tasks", "versions")

  //basically it's equivalent to path.takeWhile(s => !marathonApiKeywords.contains(s)) map createPathId
  private[akkahttp] def findSomethingLikeAppId(remainingPath: Path, extractedAppName: Path = Path.Empty): Option[PathId] = {
    def generatePathId(p: Path): PathId = {
      val trimmed = p.toString().reverse.dropWhile(_ == '/').reverse
      PathId(trimmed)
    }

    remainingPath match {
      case Path.Slash(rest) =>
        findSomethingLikeAppId(rest, extractedAppName ++ Path./)
      case p @ Path.Segment(segment, rest) =>
        if (marathonApiKeywords.contains(segment)) {
          Some(generatePathId(extractedAppName))
        } else {
          findSomethingLikeAppId(rest, extractedAppName + segment)
        }
      case Path.Empty =>
        if (extractedAppName.isEmpty) {
          None
        } else {
          Some(generatePathId(extractedAppName))
        }
    }
  }

  /**
    * Given the current root group, only extract an existing appId
    *
    * This is useful because our v2 API has an unfortunate design decision which leads to ambiguity in our URLs, such as:
    *
    *   POST /v2/apps/my-group/restart/restart
    *
    * The intention here is to restart the app named "my-group/restart"
    *
    * This directive will only extract "my-group/restart" from the path, leaving the rest
    *
    * In case there is no appId exists, it will do best-efforts attempt to find an invalid appId and generate a rejection.
    *
    * Inspired by path matching directive in akka-http.
    */
  def extractExistingAppId(rootGroup: RootGroup): Directive1[PathId] = {
    val pm = Slash ~ ExistingAppPathId(rootGroup)
    implicit val LIsTuple = pm.ev
    extract(ctx ⇒ pm(ctx.unmatchedPath) -> ctx.unmatchedPath).flatMap {
      case (Matched(rest, values), _) =>
        tprovide(values) & mapRequestContext(_ withUnmatchedPath rest)
      case (Unmatched, path) =>
        val rejection: Rejection = findSomethingLikeAppId(path)
          .map(Rejections.EntityNotFound.app(_))
          .getOrElse(Rejections.EntityNotFound())
        reject(rejection)
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

  def rejectingLeft[T](result: Directive1[Either[Rejection, T]]): Directive1[T] =
    result.flatMap {
      case Left(rej) => reject(rej)
      case Right(t) => provide(t)
    }
}
