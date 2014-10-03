package mesosphere.marathon

import akka.actor.ActorSystem
import akka.testkit.{ TestKit, TestProbe }
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ PathId, AppDefinition, AppRepository }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.when
import org.scalatest.Matchers
import org.scalatest.mock.MockitoSugar

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class SchedulerActionsTest extends TestKit(ActorSystem("TestSystem")) with MarathonSpec with Matchers with MockitoSugar {
  import system.dispatcher

  test("Reset rate limiter if application is stopped") {
    val queue = new TaskQueue
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]

    val scheduler = new SchedulerActions(
      mock[ObjectMapper],
      repo,
      mock[HealthCheckManager],
      taskTracker,
      new TaskIdUtil,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))

    when(repo.expunge(app.id)).thenReturn(Future.successful(Seq(true)))
    when(taskTracker.get(app.id)).thenReturn(Set.empty[Protos.MarathonTask])

    queue.rateLimiter.addDelay(app)

    queue.rateLimiter.getDelay(app).hasTimeLeft should be(true)

    val res = scheduler.stopApp(mock[SchedulerDriver], app)

    Await.ready(res, 1.second)

    queue.rateLimiter.getDelay(app).hasTimeLeft should be(false)
  }
}
