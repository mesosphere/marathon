package mesosphere.marathon.core.leadership

import akka.actor.{ ActorSystem, ActorRefFactory, ActorRef, Props }
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.marathon.LeadershipAbdication
import mesosphere.marathon.core.base.{ ActorsModule, ShutdownHooks }
import mesosphere.marathon.test.Mockito
import org.apache.zookeeper.ZooKeeper

/**
  * Provides an implementation of the [[LeadershipModule]] which assumes that we are always the leader.
  *
  * This simplifies tests.
  */
object AlwaysElectedLeadershipModule extends Mockito {
  def apply(shutdownHooks: ShutdownHooks = ShutdownHooks()): LeadershipModule = {
    forActorsModule(new ActorsModule(shutdownHooks))
  }

  def forActorSystem(actorSystem: ActorSystem): LeadershipModule = {
    forActorsModule(new ActorsModule(ShutdownHooks(), actorSystem))
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
