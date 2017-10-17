package mesosphere.marathon
package core.flow.impl

import akka.testkit.TestActorRef
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.flow.LaunchTokenConfig
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import org.mockito.Mockito

class OfferMatcherLaunchTokensActorTest extends AkkaUnitTest {

  case class Fixture(
      conf: LaunchTokenConfig = new LaunchTokenConfig { verify() },
      offerMatcherManager: OfferMatcherManager = mock[OfferMatcherManager]) {

    val actorRef: TestActorRef[OfferMatcherLaunchTokensActor] = TestActorRef[OfferMatcherLaunchTokensActor](
      OfferMatcherLaunchTokensActor.props(conf, offerMatcherManager)
    )

    def verifyClean(): Unit = {
      Mockito.verifyNoMoreInteractions(offerMatcherManager)
    }

  }

  "OfferMatcherLaunchTokensActor" should {
    "initially setup tokens" in new Fixture {
      Mockito.verify(offerMatcherManager).setLaunchTokens(conf.launchTokens())
      verifyClean()
    }

    "refill on running tasks without health info" in new Fixture {
      // startup
      Mockito.verify(offerMatcherManager).setLaunchTokens(conf.launchTokens())

      TaskStatusUpdateTestHelper.running().wrapped.events.foreach(system.eventStream.publish)

      Mockito.verify(offerMatcherManager).addLaunchTokens(1)
      verifyClean()
    }

    "refill on running healthy task" in new Fixture {
      // startup
      Mockito.verify(offerMatcherManager).setLaunchTokens(conf.launchTokens())

      TaskStatusUpdateTestHelper.runningHealthy().wrapped.events.foreach(system.eventStream.publish)

      Mockito.verify(offerMatcherManager).addLaunchTokens(1)
      verifyClean()
    }

    "DO NOT refill on running UNhealthy task" in new Fixture {
      // startup
      Mockito.verify(offerMatcherManager).setLaunchTokens(conf.launchTokens())

      TaskStatusUpdateTestHelper.runningUnhealthy().wrapped.events.foreach(system.eventStream.publish)
      verifyClean()
    }
  }
}
