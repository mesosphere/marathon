package mesosphere.marathon
package integration.facades

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.integration.setup.RestResult
import mesosphere.marathon.integration.setup.SprayHttpResponse._
import spray.client.pipelining._
import spray.http.HttpResponse
import spray.httpx.PlayJsonSupport

import scala.concurrent.Await._
import scala.concurrent.Future
import scala.concurrent.duration._

object MesosFacade {

  /**
    * Corresponds to parts of `state.json`.
    */
  case class ITMesosState(
    version: String,
    gitTag: Option[String],
    agents: Seq[ITAgent])

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
      "{" + resources.toSeq.sortBy(_._1).map {
        case (k, v) => s"$k: $v"
      }.mkString(", ") + " }"
    }
  }
  object ITResources {
    def empty: ITResources = new ITResources(Map.empty)
    def apply(vals: (String, Any)*): ITResources = {
      val resources: Map[String, ITResourceValue] = vals.map {
        case (id, value: Double) => id -> ITResourceScalarValue(value)
        case (id, portsString: String) => id -> ITResourcePortValue(portsString)
      }(collection.breakOut)
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

  case class ITFramework(id: String, name: String)
  case class ITFrameworks(frameworks: Seq[ITFramework])
}

class MesosFacade(url: String, waitTime: Duration = 30.seconds)(implicit val system: ActorSystem)
    extends PlayJsonSupport with StrictLogging {

  import MesosFacade._
  import MesosFormats._
  import system.dispatcher

  def state: RestResult[ITMesosState] = {
    logger.info(s"fetching state from $url")
    val pipeline = sendReceive ~> read[ITMesosState]
    result(pipeline(Get(s"$url/state.json")), waitTime)
  }

  def frameworkIds(): RestResult[Seq[String]] = {
    val pipeline = sendReceive ~> read[ITFrameworks]
    result(pipeline(Get(s"$url/frameworks")), waitTime).map(_.frameworks.map(_.id))
  }

  def terminate(frameworkId: String): HttpResponse = {
    val pipeline = sendReceive
    result(pipeline(Post(s"$url/terminate", s"frameworkId=$frameworkId")), waitTime)
  }

  def teardown(frameworkId: String): Future[HttpResponse] = {
    val pipeline = sendReceive
    pipeline(Post(s"$url/teardown", s"frameworkId=$frameworkId"))
  }
}
