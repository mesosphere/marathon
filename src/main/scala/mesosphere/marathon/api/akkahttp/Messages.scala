package mesosphere.marathon
package api.akkahttp

import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.state.Timestamp
import play.api.libs.json.Json

/**
  * Classes for generating json responses
  */
object Messages {

  /**
    * Result of the deployment
    * @param version version of the deployed app
    * @param id deployment id
    */
  case class DeploymentResult(version: Timestamp, id: String)
  object DeploymentResult extends ((Timestamp, String) => DeploymentResult) {
    def apply(plan: DeploymentPlan): DeploymentResult =
      DeploymentResult(plan.version, plan.id)

    import mesosphere.marathon.api.v2.json.Formats.TimestampFormat
    implicit val DeploymentResultFormat = Json.format[DeploymentResult]
  }
}
