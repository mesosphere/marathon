package mesosphere.marathon.core.leadership

import akka.actor.{ ActorRef, ActorSystem, Props }
import mesosphere.marathon.core.base.{ ActorsModule, ShutdownHooks, TestShutdownHooks }
import mesosphere.marathon.test.Mockito

/**
  * Provides an implementation of the [[LeadershipModule]] which assumes that we are always the leader.
  *
  * This simplifies tests.
  */
object AlwaysElectedLeadershipModule extends Mockito {
  /**
    * Create a leadership module. The caller must ensure that shutdownHooks.shutdown is called so
    * that the underlying actor system is freed.
    */
  def apply(shutdownHooks: ShutdownHooks): LeadershipModule = {
    forActorsModule(new ActorsModule(shutdownHooks))
  }

  /**
    * Create a leadership module using the given actorSystem. The caller must shutdown the given actor system
    * itself after usage.
    */
  def forActorSystem(actorSystem: ActorSystem): LeadershipModule = {
    forActorsModule(new ActorsModule(TestShutdownHooks(), actorSystem))
  }

  private[this] def forActorsModule(actorsModule: ActorsModule = new ActorsModule(ShutdownHooks())): LeadershipModule =
    {
      new AlwaysElectedLeadershipModule(actorsModule)
    }
}

private class AlwaysElectedLeadershipModule(actorsModule: ActorsModule) extends LeadershipModule {
  override def startWhenLeader(props: Props, name: String): ActorRef =
    actorsModule.actorRefFactory.actorOf(props, name)
  override def coordinator(): LeadershipCoordinator = ???
}
