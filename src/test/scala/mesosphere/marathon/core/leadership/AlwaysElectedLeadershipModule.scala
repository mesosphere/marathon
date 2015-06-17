package mesosphere.marathon.core.leadership

import akka.actor.{ ActorRefFactory, ActorRef, Props }
import mesosphere.marathon.core.base.{ ActorsModule, ShutdownHooks }

/**
  * Provides an implementation of the [[LeadershipModule]] which assumes that we are always the leader.
  *
  * This simplifies tests.
  */
object AlwaysElectedLeadershipModule {
  def apply(shutdownHooks: ShutdownHooks): LeadershipModule = {
    val actorsModule = new ActorsModule(shutdownHooks)
    new AlwaysElectedLeadershipModule(actorsModule.actorRefFactory)
  }
}

private class AlwaysElectedLeadershipModule(actorRefFactory: ActorRefFactory) extends LeadershipModule(actorRefFactory) {
  override def startWhenLeader(props: Props, name: String, preparedOnStart: Boolean = true): ActorRef =
    actorRefFactory.actorOf(props, name)
  override def coordinator(): LeadershipCoordinator = ???
}
