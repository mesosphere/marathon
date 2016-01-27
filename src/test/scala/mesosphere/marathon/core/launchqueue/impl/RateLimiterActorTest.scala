package mesosphere.marathon.core.launchqueue.impl

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, AppRepository, PathId }
import org.mockito.Mockito

import scala.concurrent.Await
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
  private[this] var limiterRef: ActorRef = _

  before {
    actorSystem = ActorSystem()
    clock = ConstantClock()
    rateLimiter = Mockito.spy(new RateLimiter(clock))
    taskTracker = mock[TaskTracker]
    appRepository = mock[AppRepository]
    updateReceiver = TestProbe()
    val props = RateLimiterActor.props(rateLimiter, appRepository, updateReceiver.ref)
    limiterRef = actorSystem.actorOf(props, "limiter")
  }

  after {
    actorSystem.shutdown()
    actorSystem.awaitTermination()
  }
}
