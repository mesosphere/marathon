package mesosphere.marathon
package core.instance

import play.api.libs.json.{Format, JsError, JsResult, JsString, JsSuccess, JsValue, JsonValidationError, Reads, Writes}

/**
  * Defines goal of the instance.
  * Goal is set by an orchestration layer and interpreted by scheduler layer.
  * In the end it is used by low-level scheduler to make scheduling decisions e.g. should the task associated with this instance be launched or killed?
  */
sealed trait Goal extends Product with Serializable

object Goal {

  /**
    * There should always be a running Mesos task associated by instance in this state.
    * Instance with Suspended Goal might be changed to both [[Running]] or [[Stopped]] by orchestration layer.
    */
  case object Running extends Goal

  /**
    * Tasks associated with this instance shall be killed, but the instance needs to be kept in the state.
    * This is typically needed for resident instances, where we need to persist the reservation for re-launch.
    * Instance with Suspended Goal might be changed to both [[Running]] or [[Decommissioned]].
    */
  case object Stopped extends Goal

  /**
    * All tasks associated with this instance shall be killed, and after they're reportedly terminal, the instance shall be removed because it's no longer needed.
    * This is typically used for ephemeral instances, when scaling down, deleting a service or upgrading.
    * This is terminal Goal, instance with this goal won't transition into any other Goal from now on.
    */
  case object Decommissioned extends Goal

  private val goalReader = new Reads[Goal] {
    override def reads(json: JsValue): JsResult[Goal] = {
      json match {
        case JsString(value) => value.toLowerCase match {
          case "running" => JsSuccess(Running)
          case "stopped" => JsSuccess(Stopped)
          case "decommissioned" => JsSuccess(Decommissioned)
          case v => JsError(JsonValidationError("instance.state.goal", s"Unknown goal $v - expecting string with one of the following values 'running', 'stopped', 'decommissioned'"))
        }
        case v => JsError(JsonValidationError("instance.state.goal", s"Unknown goal $v - expecting string with one of the following values 'running', 'stopped', 'decommissioned'"))
      }
    }
  }

  implicit val goalFormat = Format[Goal](
    goalReader,
    Writes(goal => JsString(goal.toString)))

}

