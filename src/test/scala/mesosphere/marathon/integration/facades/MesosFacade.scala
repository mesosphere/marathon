package mesosphere.marathon.integration.facades

import akka.actor.ActorSystem
import mesosphere.marathon.integration.setup.RestResult
import spray.client.pipelining._
import spray.httpx.PlayJsonSupport
import mesosphere.marathon.integration.setup.SprayHttpResponse._

import scala.concurrent.Await._
import scala.concurrent.duration._

object MesosFacade {

  /**
    * Corresponds to parts of `state.json`.
    */
  case class ITMesosState(
    version: String,
    gitTag: Option[String],
    agents: Iterable[ITAgent])

  case class ITAgent(
    id: String,
    resources: ITResources,
    usedResources: ITResources,
    offeredResources: ITResources,
    reservedResourcesByRole: Map[String, ITResources],
    unreservedResources: ITResources)

  case class ITResources(resources: Map[String, ITResourceValue]) {
    def isEmpty: Boolean = resources.isEmpty || resources.values.forall(_.isEmpty)

    override def toString: String = {
      s"{" + resources.toSeq.sortBy(_._1).map {
        case (k, v) => s"$k: $v"
      }.mkString(", ") + " }"
    }
  }
  object ITResources {
    def empty: ITResources = new ITResources(Map.empty)
    def apply(vals: (String, Any)*): ITResources = {
      val resources = vals.toMap.mapValues {
        case value: Double       => ITResourceScalarValue(value)
        case portsString: String => ITResourcePortValue(portsString)
      }
      ITResources(resources)
    }
  }

  sealed trait ITResourceValue {
    def isEmpty: Boolean
  }
  case class ITResourceScalarValue(value: Double) extends ITResourceValue {
    override def isEmpty: Boolean = value == 0
    override def toString: String = value.toString
  }
  case class ITResourcePortValue(portString: String) extends ITResourceValue {
    override def isEmpty: Boolean = false
    override def toString: String = '"' + portString + '"'
  }
}

class MesosFacade(url: String, waitTime: Duration = 30.seconds)(implicit val system: ActorSystem)
    extends PlayJsonSupport {

  import system.dispatcher
  import MesosFacade._
  import MesosFormats._

  def state: RestResult[ITMesosState] = {
    val pipeline = sendReceive ~> read[ITMesosState]
    result(pipeline(Get(s"$url/state.json")), waitTime)
  }
}
