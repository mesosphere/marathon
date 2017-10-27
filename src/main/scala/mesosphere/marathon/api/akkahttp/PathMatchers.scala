package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.Uri.Path
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ Group, PathId, RootGroup, Timestamp }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatcher.{ Matched, Unmatched }
import akka.http.scaladsl.server.PathMatcher1

import scala.annotation.tailrec

object PathMatchers {
  import akka.http.scaladsl.server.PathMatcher._

  /**
    * Matches the remaining path and transforms it into task id
    */
  final val RemainingTaskId = Remaining.map(s => Task.Id(s))

  /**
    * Tries to match the remaining path as Timestamp
    */
  final val Version = Segment.flatMap(string =>
    try Some(Timestamp(string))
    catch { case _: IllegalArgumentException => None }
  )

  private val marathonApiKeywords = Set("restart", "tasks", "versions", "delay")

  /**
    * Matches everything what's coming before api keywords as PathId
    */
  case object AppPathIdLike extends PathMatcher1[PathId] {

    @tailrec def iter(reversePieces: List[String], remaining: Path, consumedSlash: Option[Path] = None): Matching[Tuple1[PathId]] = remaining match {
      case slash @ Path.Slash(rest) =>
        iter(reversePieces, rest, Some(slash))
      case Path.Segment(segment, rest) if !marathonApiKeywords(segment) =>
        iter(segment :: reversePieces, rest)
      case _ if reversePieces.isEmpty =>
        Unmatched
      case remaining =>
        Matched(
          consumedSlash.getOrElse(remaining),
          Tuple1(PathId.sanitized(reversePieces.reverse)))
    }

    def iter(remaining: Path): Matching[Tuple1[PathId]] = iter(Nil, remaining)

    override def apply(path: Path): Matching[Tuple1[PathId]] = iter(path)
  }

  /**
    * Matches anything until ::. The remaining path will be :: plus everything that follows :: in the original path.
    * The matched path up until :: will be extracted.
    *
    * Note: This makes the use of :: illegal in a pods path.
    */
  case object PodsPathIdLike extends PathMatcher1[String] {
    // Simple reg ex that matches anything before and after ::
    val keywordMatcher = "^(.*)::(.*)$".r

    @tailrec def iter(accumulatedPathId: String, remaining: Path): Matching[Tuple1[String]] = remaining match {
      case Path.Slash(rest) =>
        if (rest.isEmpty) Unmatched
        else iter(accumulatedPathId + "/", rest)
      case Path.Segment(segment, rest) =>
        segment match {
          case keywordMatcher(before, keyword) =>
            Matched(s"::$keyword" :: rest, Tuple1(accumulatedPathId + before))
          case _ =>
            iter(accumulatedPathId + segment, rest)
        }
      case _ => Matched(remaining, Tuple1(accumulatedPathId))
    }

    def iter(remaining: Path): Matching[Tuple1[String]] = iter("", remaining)

    override def apply(path: Path) = iter(path)
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
    * Given the url above, this matcher will only consume "my-group/restart" from the path,
    * leaving the rest of the matcher to match the rest
    */
  case class ExistingRunSpecId(rootGroup: () => RootGroup) extends PathMatcher1[PathId] {
    import akka.http.scaladsl.server.PathMatcher._

    @tailrec final def iter(collected: Vector[String], remaining: Path, group: Group): Matching[Tuple1[PathId]] = remaining match {
      case Path.Slash(rest) =>
        iter(collected, rest, group)
      case Path.Segment(segment, rest) =>
        val rawPathId = collected :+ segment
        val pathId = PathId.sanitized(rawPathId, true)
        group.app(pathId) match {
          case Some(_) => Matched(rest, Tuple1(pathId))
          case None => iter(rawPathId, rest, group)
        }
      case _ =>
        Unmatched
    }

    override def apply(path: Path) = iter(Vector.empty, path, rootGroup())
  }

  /**
    * Path matcher, that matches a segment only, if it is defined in the given set.
    * @param set the allowed path segments.
    */
  class PathIsAvailableInSet(set: Set[String]) extends PathMatcher1[String] {
    def apply(path: Path) = path match {
      case Path.Segment(segment, tail) if set(segment) => Matched(tail, Tuple1(segment))
      case _ => Unmatched
    }
  }

}
