package mesosphere.marathon
package core.launchqueue.impl

import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AppDefinition, BackoffStrategy, PathId}
import org.mockito.Mockito

import scala.concurrent.duration._

class RateLimiterActorTest extends AkkaUnitTest {
  private val backoff = 10.seconds
  private val backoffStrategy = BackoffStrategy(backoff = backoff, factor = 2.0)
  private[this] val app = AppDefinition(id = PathId("/test"), backoffStrategy = backoffStrategy)

  private[this] implicit val timeout: Timeout = 3.seconds

  case class Fixture(
      launchQueueConfig: LaunchQueueConfig = new LaunchQueueConfig { verify() },
      clock: SettableClock = new SettableClock(),
      instanceTracker: InstanceTracker = mock[InstanceTracker],
      updateReceiver: TestProbe = TestProbe()) {
    val rateLimiter: RateLimiter = Mockito.spy(new RateLimiter(clock))
    val props = RateLimiterActor.props(rateLimiter)
    val limiterRef = system.actorOf(props)
    limiterRef.tell(RateLimiterActor.Subscribe, updateReceiver.ref)
  }

  "RateLimiterActor" should {
    "GetDelay gets current delay" in new Fixture {
      rateLimiter.addDelay(app)

      val delay = (limiterRef ? RateLimiterActor.GetDelay(app.configRef)).futureValue.asInstanceOf[RateLimiter.DelayUpdate]
      assert(delay.delayUntil == Some(clock.now() + backoff))
    }

    "AddDelay increases delay and sends update" in new Fixture {
      limiterRef ! RateLimiterActor.AddDelay(app)
      updateReceiver.expectMsg(RateLimiter.DelayUpdate(app.configRef, Some(clock.now() + backoff)))
      val delay = (limiterRef ? RateLimiterActor.GetDelay(app.configRef)).futureValue.asInstanceOf[RateLimiter.DelayUpdate]
      assert(delay.delayUntil == Some(clock.now() + backoff))
    }

    "ResetDelay resets delay and sends update" in new Fixture {
      limiterRef ! RateLimiterActor.AddDelay(app)
      updateReceiver.expectMsg(RateLimiter.DelayUpdate(app.configRef, Some(clock.now() + backoff)))
      limiterRef ! RateLimiterActor.ResetDelay(app)
      updateReceiver.expectMsg(RateLimiter.DelayUpdate(app.configRef, None))
      val delay = (limiterRef ? RateLimiterActor.GetDelay(app.configRef)).futureValue.asInstanceOf[RateLimiter.DelayUpdate]
      assert(delay.delayUntil == None)
    }
  }
}
