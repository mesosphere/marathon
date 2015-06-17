package mesosphere.marathon.core.launchqueue.impl

import java.lang.Double

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.task.bus.{ TaskStatusUpdateTestHelper, TaskStatusObservables }
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.state.{ AppDefinition, AppRepository, PathId }
import mesosphere.marathon.tasks.TaskTracker
import org.mockito.Mockito
import rx.lang.scala.Subject
import rx.lang.scala.subjects.PublishSubject

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._

class RateLimiterActorTest extends MarathonSpec {

  test("GetDelay gets current delay") {
    rateLimiter.addDelay(app)

    val delay = askLimiter(RateLimiterActor.GetDelay(app)).asInstanceOf[RateLimiterActor.DelayUpdate]
    assert(delay.delayUntil == clock.now() + backoff)
  }

  test("AddDelay increases delay and sends update") {
    limiterRef ! RateLimiterActor.AddDelay(app)
    updateReceiver.expectMsg(RateLimiterActor.DelayUpdate(app, clock.now() + backoff))
    val delay = askLimiter(RateLimiterActor.GetDelay(app)).asInstanceOf[RateLimiterActor.DelayUpdate]
    assert(delay.delayUntil == clock.now() + backoff)
  }

  test("ResetDelay resets delay and sends update") {
    limiterRef ! RateLimiterActor.AddDelay(app)
    updateReceiver.expectMsg(RateLimiterActor.DelayUpdate(app, clock.now() + backoff))
    limiterRef ! RateLimiterActor.ResetDelay(app)
    updateReceiver.expectMsg(RateLimiterActor.DelayUpdate(app, clock.now()))
    val delay = askLimiter(RateLimiterActor.GetDelay(app)).asInstanceOf[RateLimiterActor.DelayUpdate]
    assert(delay.delayUntil == clock.now())
  }

  test("Delay gets increased if task dies unexpectedly") {
    val taskUpdate: TaskStatusUpdate = TaskStatusUpdateTestHelper.finished.withAppId(app.id.toString).wrapped
    val version: String = clock.now().toString
    val marathonTask = MarathonTask.newBuilder()
      .setId(taskUpdate.taskId.getValue)
      .setVersion(version)
      .build()
    Mockito.when(taskTracker.fetchTask(app.id, taskUpdate.taskId.getValue)).thenReturn(Some(marathonTask))
    Mockito.when(appRepository.app(app.id, clock.now())).thenReturn(Future.successful(Some(app)))

    // wait for startup
    askLimiter(RateLimiterActor.GetDelay(app)).asInstanceOf[RateLimiterActor.DelayUpdate]

    forAllStatusUpdates.onNext(taskUpdate)

    updateReceiver.expectMsg(RateLimiterActor.DelayUpdate(app, clock.now() + backoff))
    val delay = askLimiter(RateLimiterActor.GetDelay(app)).asInstanceOf[RateLimiterActor.DelayUpdate]
    assert(delay.delayUntil == clock.now() + backoff)

    Mockito.verify(taskTracker).fetchTask(app.id, taskUpdate.taskId.getValue)
    Mockito.verify(appRepository).app(app.id, clock.now())
  }

  test("Delay gets NOT increased if task is killed") {
    val taskUpdate: TaskStatusUpdate = TaskStatusUpdateTestHelper.killed.withAppId(app.id.toString).wrapped
    val version: String = clock.now().toString
    val marathonTask = MarathonTask.newBuilder()
      .setId(taskUpdate.taskId.getValue)
      .setVersion(version)
      .build()
    Mockito.when(taskTracker.fetchTask(app.id, taskUpdate.taskId.getValue)).thenReturn(Some(marathonTask))
    Mockito.when(appRepository.app(app.id, clock.now())).thenReturn(Future.successful(Some(app)))

    // wait for startup
    askLimiter(RateLimiterActor.GetDelay(app)).asInstanceOf[RateLimiterActor.DelayUpdate]

    forAllStatusUpdates.onNext(taskUpdate)

    val delay = askLimiter(RateLimiterActor.GetDelay(app)).asInstanceOf[RateLimiterActor.DelayUpdate]
    assert(delay.delayUntil == clock.now())

    updateReceiver.expectNoMsg(0.seconds)
  }

  private[this] def askLimiter(message: Any): Any = {
    Await.result(limiterRef ? message, 3.seconds)
  }

  private val backoff: FiniteDuration = 10.seconds
  private val backoffFactor: Double = 2.0
  private[this] val app = AppDefinition(id = PathId("/test"), backoff = backoff, backoffFactor = backoffFactor)

  private[this] implicit val timeout: Timeout = 3.seconds
  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var clock: ConstantClock = _
  private[this] var rateLimiter: RateLimiter = _
  private[this] var taskTracker: TaskTracker = _
  private[this] var appRepository: AppRepository = _
  private[this] var updateReceiver: TestProbe = _
  private[this] var taskStatusObservables: TaskStatusObservables = _
  private[this] var forAllStatusUpdates: Subject[TaskStatusUpdate] = _
  private[this] var limiterRef: ActorRef = _

  before {
    actorSystem = ActorSystem()
    clock = ConstantClock()
    rateLimiter = Mockito.spy(new RateLimiter(clock))
    taskTracker = mock[TaskTracker]
    appRepository = mock[AppRepository]
    updateReceiver = TestProbe()
    taskStatusObservables = mock[TaskStatusObservables]
    forAllStatusUpdates = PublishSubject()
    Mockito.when(taskStatusObservables.forAll).thenReturn(forAllStatusUpdates)
    val props = RateLimiterActor.props(
      rateLimiter, taskTracker, appRepository, updateReceiver.ref, taskStatusObservables)
    limiterRef = actorSystem.actorOf(props, "limiter")
  }

  after {
    actorSystem.shutdown()
    actorSystem.awaitTermination()
  }
}
