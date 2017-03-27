package mesosphere.marathon
package integration.setup

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.integration.facades.ITEnrichedTask
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Seconds, Span }
import play.api.libs.json.{ JsValue, Json }
import spray.client.pipelining._
import spray.http.HttpResponse

import scala.concurrent.duration.{ Duration, _ }

class ServiceMockFacade private (val task: ITEnrichedTask, waitTime: Duration = 30.seconds)(implicit system: ActorSystem) extends StrictLogging {
  import mesosphere.marathon.core.async.ExecutionContexts.global

  private val baseUrl = s"http://${task.host}:${task.ports.map(_.head).get}"

  private val pipeline = sendReceive

  def continue(): RestResult[HttpResponse] = {
    logger.info(s"Continue with the service migration: $baseUrl/v1/plan/continue")
    RestResult.await(pipeline(Post(s"$baseUrl/v1/plan/continue")), waitTime)
  }

  def plan(): RestResult[JsValue] = {
    RestResult.await(pipeline(Get(s"$baseUrl/v1/plan")), waitTime).map(_.entity.asString).map(Json.parse)
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
  def apply(loadTasks: => Seq[ITEnrichedTask])(predicate: (ITEnrichedTask) => Boolean)(implicit system: ActorSystem): ServiceMockFacade = eventually {
    val newTask = loadTasks.find(predicate(_)).get
    val serviceFacade = new ServiceMockFacade(newTask)
    serviceFacade.plan()
    serviceFacade
  }
}
