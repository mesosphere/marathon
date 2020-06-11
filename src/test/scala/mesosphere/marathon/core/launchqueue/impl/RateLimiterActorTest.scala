package mesosphere.marathon
package core.launchqueue.impl

import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.launchqueue.LaunchQueueConfig
import mesosphere.marathon.core.launchqueue.impl.RateLimiter.Delay
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, BackoffStrategy}
import mesosphere.marathon.test.SettableClock
import org.mockito.Mockito

import scala.concurrent.duration._

class RateLimiterActorTest extends AkkaUnitTest {
  private val backoff = 10.seconds
  private val backoffStrategy = BackoffStrategy(backoff = backoff, factor = 2.0)
  private[this] val app = AppDefinition(id = AbsolutePathId("/test"), role = "*", backoffStrategy = backoffStrategy)

  private[this] implicit val timeout: Timeout = 3.seconds

  case class Fixture(
      launchQueueConfig: LaunchQueueConfig = new LaunchQueueConfig { verify() },
      clock: SettableClock = new SettableClock(),
      instanceTracker: InstanceTracker = mock[InstanceTracker],
      updateReceiver: TestProbe = TestProbe()
  ) {
    val rateLimiter: RateLimiter = Mockito.spy(new RateLimiter(clock))
    val props = RateLimiterActor.props(rateLimiter)
    val limiterRef = system.actorOf(props)
    limiterRef.tell(RateLimiterActor.Subscribe, updateReceiver.ref)
  }

  "RateLimiterActor" should {
    "GetDelay gets current delay" in new Fixture {
      rateLimiter.addDelay(app)
      val delayUpdate = (limiterRef ? RateLimiterActor.GetDelay(app.configRef)).futureValue.asInstanceOf[RateLimiter.DelayUpdate]
      delayUpdate.delay.value.deadline should be(clock.now() + backoff)
    }

    "AddDelay increases delay and sends update" in new Fixture {
      limiterRef ! RateLimiterActor.AddDelay(app)
      val delay = Delay(clock.now(), app.backoffStrategy.backoff, app.backoffStrategy.maxLaunchDelay)
      updateReceiver.expectMsg(RateLimiter.DelayUpdate(app.configRef, Some(delay)))
      val delayUpdate = (limiterRef ? RateLimiterActor.GetDelay(app.configRef)).futureValue.asInstanceOf[RateLimiter.DelayUpdate]
      delayUpdate.delay.value shouldEqual delay
    }

    "ResetDelay resets delay and sends update" in new Fixture {
      limiterRef ! RateLimiterActor.AddDelay(app)
      val delay = Delay(clock.now(), app.backoffStrategy.backoff, app.backoffStrategy.maxLaunchDelay)
      updateReceiver.expectMsg(RateLimiter.DelayUpdate(app.configRef, Some(delay)))
      limiterRef ! RateLimiterActor.ResetDelay(app)
      updateReceiver.expectMsg(RateLimiter.DelayUpdate(app.configRef, None))
      val delayUpdate = (limiterRef ? RateLimiterActor.GetDelay(app.configRef)).futureValue.asInstanceOf[RateLimiter.DelayUpdate]
      delayUpdate.delay shouldBe empty
    }
  }
}
