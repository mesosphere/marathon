package mesosphere.marathon

import akka.actor.ActorSystem
import akka.testkit.{ TestKit, TestProbe }
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ PathId, AppDefinition, AppRepository }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import org.apache.mesos.Protos.{ TaskState, TaskID, TaskStatus }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ times, verify, when }
import org.scalatest.Matchers
import org.scalatest.mock.MockitoSugar

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.collection.JavaConverters._

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

  test("Task reconciliation sends known tasks and empty list") {
    val queue = new TaskQueue
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]
    val driver = mock[SchedulerDriver]

    val status = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue("task_1"))
      .setState(TaskState.TASK_RUNNING)
      .build()

    val task = MarathonTask.newBuilder
      .setId("task_1")
      .setStatus(status)
      .build()

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

    when(taskTracker.get(app.id)).thenReturn(Set(task))
    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))
    when(taskTracker.list).thenReturn(Map(app.id -> TaskTracker.App(app.id, Set(task), shutdown = false)))

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver).reconcileTasks(Set(status).asJava)
    verify(driver).reconcileTasks(java.util.Arrays.asList())
  }

  test("Task reconciliation only one empty list, when no tasks are present in Marathon") {
    val queue = new TaskQueue
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]
    val driver = mock[SchedulerDriver]

    val status = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue("task_1"))
      .setState(TaskState.TASK_RUNNING)
      .build()

    val task = MarathonTask.newBuilder
      .setId("task_1")
      .setStatus(status)
      .build()

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

    when(taskTracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    when(repo.allPathIds()).thenReturn(Future.successful(Seq()))
    when(taskTracker.list).thenReturn(Map.empty[PathId, TaskTracker.App])

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver, times(1)).reconcileTasks(java.util.Arrays.asList())
  }
}
