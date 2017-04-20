package mesosphere.marathon
package api.v2

import com.wix.accord._
import play.api.libs.json.{ JsError, JsResult }

object ValidationHelper {
  case class ViolationMessageAndPath(message: String, path: Option[String]) {
    override def toString: String = path.map(p => s"Path: $p. Error message: $message")
      .getOrElse(s"Error message: $message")
  }

  def getAllRuleConstrains(r: Result): Set[ViolationMessageAndPath] = {
    r match {
      case f: Failure => f.violations.flatMap(Validation.allRuleViolationsWithFullDescription(_))
        .map(r => ViolationMessageAndPath(r.constraint, r.description))
      case _ => Set.empty
    }
  }

  def getAllRuleConstrains(r: JsResult[_]): Set[ViolationMessageAndPath] = {
    r match {
      case f: JsError => f.errors.flatMap {
        case (path, errors) =>
          errors.flatMap { err =>
            val messages = err.messages
            messages.map { msg =>
              ViolationMessageAndPath(msg, Option(path.toString))
            }
          }
      }(collection.breakOut)
      case _ => Set.empty
    }
  }
}
