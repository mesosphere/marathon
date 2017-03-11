package mesosphere.marathon
package core.leadership

import akka.actor.{ ActorRef, ActorRefFactory, Props }
import mesosphere.marathon.core.base.{ ActorsModule }
import mesosphere.marathon.test.Mockito

/**
  * Provides an implementation of the [[LeadershipModule]] which assumes that we are always the leader.
  *
  * This simplifies tests.
  */
object AlwaysElectedLeadershipModule extends Mockito {
  /**
    * Create a leadership module using the given actorSystem. The caller must shutdown the given actor system
    * itself after usage.
    */
  def forRefFactory(actorRefFactory: ActorRefFactory): LeadershipModule = {
    forActorsModule(new ActorsModule(actorRefFactory))
  }

  private[this] def forActorsModule(actorsModule: ActorsModule): LeadershipModule =
    {
      new AlwaysElectedLeadershipModule(actorsModule)
    }
}

private class AlwaysElectedLeadershipModule(actorsModule: ActorsModule) extends LeadershipModule {
  override def startWhenLeader(props: Props, name: String): ActorRef =
    actorsModule.actorRefFactory.actorOf(props, name)
  override def coordinator(): LeadershipCoordinator = ???
}
