package mesosphere.marathon
package api.v2

import com.wix.accord._
import mesosphere.marathon.api.v2.Validation.ConstraintViolation
import mesosphere.marathon.state.ResourceRole
import mesosphere.marathon.util.RoleSettings
import play.api.libs.json.{JsError, JsResult}

object ValidationHelper {

  def getAllRuleConstraints(r: Result): Set[ConstraintViolation] = Validation.allViolations(r).toSet

  def getAllRuleConstraints(r: JsResult[_]): Set[ConstraintViolation] = {
    r match {
      case f: JsError => f.errors.flatMap {
        case (path, errors) =>
          errors.flatMap { err =>
            val messages = err.messages
            messages.map { msg =>
              ConstraintViolation(path.toString, msg)
            }
          }
      }(collection.breakOut)
      case _ => Set.empty
    }
  }

  def roleSettings = RoleSettings(validRoles = Set(ResourceRole.Unreserved), defaultRole = ResourceRole.Unreserved)
  def roleSettings(role: String) = RoleSettings(validRoles = Set(role, ResourceRole.Unreserved), defaultRole = role)
}
