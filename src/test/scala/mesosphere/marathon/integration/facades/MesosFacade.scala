package mesosphere.marathon
package integration.facades

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.{ Get, Post }
import akka.http.scaladsl.model.HttpResponse
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import mesosphere.marathon.integration.setup.RestResult
import mesosphere.marathon.integration.setup.AkkaHttpResponse._

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
    attributes: ITAttributes,
    resources: ITResources,
    usedResources: ITResources,
    offeredResources: ITResources,
    reservedResourcesByRole: Map[String, ITResources],
    unreservedResources: ITResources)

  case class ITAttributes(attributes: Map[String, ITResourceValue])

  object ITAttributes {
    def empty: ITAttributes = new ITAttributes(Map.empty)
    def apply(vals: (String, Any)*): ITAttributes = {
      val attributes: Map[String, ITResourceValue] = vals.map {
        case (id, value: Double) => id -> ITResourceScalarValue(value)
        case (id, portsString: String) => id -> ITResourcePortValue(portsString)
      }(collection.breakOut)
      ITAttributes(attributes)
    }
  }

  case class ITResources(resources: Map[String, ITResourceValue]) {
    def isEmpty: Boolean = resources.isEmpty || resources.values.forall(_.isEmpty)
    def nonEmpty: Boolean = !isEmpty

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

class MesosFacade(url: String, implicit val waitTime: FiniteDuration = 30.seconds)(implicit val system: ActorSystem, materializer: Materializer)
    extends PlayJsonSupport with StrictLogging {

  import MesosFacade._
  import MesosFormats._
  import system.dispatcher

  def state: RestResult[ITMesosState] = {
    logger.info(s"fetching state from $url")
    result(requestFor[ITMesosState](Get(s"$url/state.json")), waitTime)
  }

  def frameworkIds(): RestResult[Seq[String]] = {
    result(requestFor[ITFrameworks](Get(s"$url/frameworks")), waitTime).map(_.frameworks.map(_.id))
  }

  def terminate(frameworkId: String): HttpResponse = {
    result(request(Post(s"$url/terminate", s"frameworkId=$frameworkId")), waitTime).value
  }

  def teardown(frameworkId: String): Future[HttpResponse] = {
    request(Post(s"$url/teardown", s"frameworkId=$frameworkId")).map(_.value)
  }
}
