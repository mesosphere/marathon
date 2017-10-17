package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.headers.CustomHeader

@SuppressWarnings(Array("ClassNames"))
object Headers {

  /**
    * Custom Header used to signify a deployment Id when created
    */
  case class `Marathon-Deployment-Id`(planId: String) extends CustomHeader {
    override def name(): String = "Marathon-Deployment-Id"
    override def value(): String = planId
    override def renderInResponses(): Boolean = true
    override def renderInRequests(): Boolean = false
  }

  final case class `X-Marathon-Leader`(hostPort: String) extends CustomHeader {
    override def name(): String = "X-Marathon-Leader"
    override def value: String = hostPort
    override def renderInRequests = false
    override def renderInResponses = true
  }

  final case class `X-Marathon-Via`(via: String) extends CustomHeader {
    override def name(): String = "X-Marathon-Via"
    override def value: String = via
    override def renderInRequests = false
    override def renderInResponses = true
  }
}
