package mesosphere.marathon
package integration.setup

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.client.RequestBuilding.{ Get, Post }
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.setup.AkkaHttpResponse._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Seconds, Span }
import play.api.libs.json.JsValue

import scala.concurrent.Await
import scala.concurrent.duration._

class ServiceMockFacade private (val task: ITEnrichedTask, implicit val waitTime: FiniteDuration = 30.seconds)(implicit system: ActorSystem, mat: Materializer) extends StrictLogging {
  import mesosphere.marathon.core.async.ExecutionContexts.global

  private val baseUrl = s"http://${task.host}:${task.ports.map(_.head).get}"

  def continue(): RestResult[HttpResponse] = {
    logger.info(s"Continue with the service migration: $baseUrl/v1/plan/continue")
    Await.result(request(Post(s"$baseUrl/v1/plan/continue")), waitTime)
  }

  def plan(): RestResult[JsValue] = {
    Await.result(requestFor[JsValue](Get(s"$baseUrl/v1/plan")), waitTime)
  }
}

object ServiceMockFacade extends Eventually {
  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(30, Seconds))

  /**
    * Eventually creates a ServiceMockFacade if a task can be found that responds to http requests.
    * @param loadTasks A function that is able to load tasks. This is by name on purpose because it will be polled
    *                  until a task can be loaded that matches the given predicate.
    *                  This will usually be something like marathonFacade.tasks(runSpecId)
    * @param predicate The predicate that a task must fulfil in order to build wrap a ServiceMockFacade around it
    *                  Use the predicate to look for a task in a certain state, or check it's ID for (un-)equality.
    */
  def apply(loadTasks: => Seq[ITEnrichedTask])(predicate: (ITEnrichedTask) => Boolean)(implicit system: ActorSystem, mat: Materializer): ServiceMockFacade = eventually {
    val newTask = loadTasks.find(predicate(_)).get
    val serviceFacade = new ServiceMockFacade(newTask)
    serviceFacade.plan()
    serviceFacade
  }
}
