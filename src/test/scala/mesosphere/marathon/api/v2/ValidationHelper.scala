package mesosphere.marathon.api.v2

import com.wix.accord._

object ValidationHelper {
  case class ViolationMessageAndPath(message: String, path: Option[String]) {
    override def toString: String = path.map(p => s"Path: $p. Error message: $message")
      .getOrElse(s"Error message: $message")
  }

  def getAllRuleConstrains(r: Result): Set[ViolationMessageAndPath] = {
    r match {
      case f: Failure => f.violations.flatMap(Validation.allRuleViolationsWithFullDescription(_))
        .map(r => ViolationMessageAndPath(r.constraint, r.description))
      case _ => Set.empty[ViolationMessageAndPath]
    }
  }
}
