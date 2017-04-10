package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.headers.CustomHeader

object Headers {
  /**
    * Custom Header used to signify a deployment Id when created
    */
  @SuppressWarnings(Array("ClassNames"))
  case class `Marathon-Deployment-Id`(planId: String) extends CustomHeader {
    override def name(): String = "Marathon-Deployment-Id"
    override def value(): String = planId
    override def renderInResponses(): Boolean = true
    override def renderInRequests(): Boolean = false
  }
}
