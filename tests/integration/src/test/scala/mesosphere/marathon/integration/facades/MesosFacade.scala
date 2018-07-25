package mesosphere.marathon
package integration.facades

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.{Get, Post}
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import mesosphere.marathon.integration.setup.RestResult
import mesosphere.marathon.integration.setup.AkkaHttpResponse._

import scala.concurrent.Await._
import scala.concurrent.duration._

object MesosFacade {

  /**
    * Corresponds to parts of `state.json`.
    */
  case class ITMesosState(
      version: String,
      gitTag: Option[String],
      agents: Seq[ITAgent],
      frameworks: Seq[ITFramework],
      completed_frameworks: Seq[ITFramework],
      unregistered_frameworks: Seq[ITFramework])

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
        case (id, value: String) => id -> ITResourceStringValue(value)
        case (id, value: Any) => throw new IllegalArgumentException(s"Unexpected attribute id=$id value=$value")
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
        case (id, value: String) => id -> ITResourceStringValue(value)
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
  case class ITResourceStringValue(portString: String) extends ITResourceValue {
    override def isEmpty: Boolean = false
    override def toString: String = '"' + portString + '"'
  }

  case class ITask(id: String, status: String)

  case class ITFramework(id: String, name: String, tasks: Seq[ITask])
  case class ITFrameworks(
      frameworks: Seq[ITFramework],
      completed_frameworks: Seq[ITFramework],
      unregistered_frameworks: Seq[ITFramework])
}

class MesosFacade(val url: String, val waitTime: FiniteDuration = 30.seconds)(implicit val system: ActorSystem, materializer: Materializer)
  extends PlayJsonSupport with StrictLogging {

  import MesosFacade._
  import MesosFormats._
  import system.dispatcher

  // `waitTime` is passed implicitly to the `request` and `requestFor` methods
  implicit val requestTimeout = waitTime
  def state: RestResult[ITMesosState] = {
    logger.info(s"fetching state from $url")
    result(requestFor[ITMesosState](Get(s"$url/state.json")), waitTime)
  }

  def frameworks(): RestResult[ITFrameworks] = {
    result(requestFor[ITFrameworks](Get(s"$url/frameworks")), waitTime)
  }

  def frameworkIds(): RestResult[Seq[String]] = {
    frameworks().map(_.frameworks.map(_.id))
  }

  def completedFrameworkIds(): RestResult[Seq[String]] = {
    frameworks().map(_.completed_frameworks.map(_.id))
  }

  def teardown(frameworkId: String): HttpResponse = {
    result(request(Post(s"$url/teardown", HttpEntity(s"frameworkId=$frameworkId"))), waitTime).value
  }
}
