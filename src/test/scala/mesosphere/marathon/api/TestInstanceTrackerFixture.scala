package mesosphere.marathon
package api

import java.time.Clock

import akka.actor.ActorSystem
import mesosphere.marathon.core.task.tracker.impl.{InstanceTrackerActor, InstanceTrackerDelegate}
import mesosphere.marathon.state.RootGroup
import mesosphere.marathon.storage.repository.{InstanceRepository, InstanceView}
import mesosphere.marathon.test.TestCrashStrategy

import scala.concurrent.ExecutionContext

class TestInstanceTrackerFixture(
    initialRoot: RootGroup = RootGroup.empty,
    authenticated: Boolean = true,
    authorized: Boolean = true,
    authFn: Any => Boolean = _ => true,
    val clock: Clock = Clock.systemUTC()
)(implicit as: ActorSystem, ec: ExecutionContext)
    extends TestGroupManagerFixture(initialRoot, authenticated = authenticated, authorized = authorized, authFn = authFn) {

  val crashStrategy = new TestCrashStrategy
  val instanceRepository = InstanceRepository.inMemRepository(store)
  val instanceView = InstanceView(instanceRepository, groupRepository)
  val instanceTrackerActor = as.actorOf(
    InstanceTrackerActor
      .props(metrics = metrics, config = config, steps = Nil, repository = instanceView, clock = clock, crashStrategy = crashStrategy)
  )
  val instanceTracker = new InstanceTrackerDelegate(metrics = metrics, clock = clock, config = config, instanceTrackerActor)
}
