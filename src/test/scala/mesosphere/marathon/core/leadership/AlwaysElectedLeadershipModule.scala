package mesosphere.marathon.core.leadership

import akka.actor.{ ActorRefFactory, ActorRef, Props }
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.marathon.LeadershipAbdication
import mesosphere.marathon.core.base.{ ActorsModule, ShutdownHooks }
import mesosphere.marathon.test.Mockito

/**
  * Provides an implementation of the [[LeadershipModule]] which assumes that we are always the leader.
  *
  * This simplifies tests.
  */
object AlwaysElectedLeadershipModule extends Mockito {
  def apply(shutdownHooks: ShutdownHooks): LeadershipModule = {
    val actorsModule = new ActorsModule(shutdownHooks)
    val zk = mock[ZooKeeperClient]
    val leader = mock[LeadershipAbdication]
    new AlwaysElectedLeadershipModule(actorsModule.actorRefFactory, zk, leader)
  }
}

private class AlwaysElectedLeadershipModule(actorRefFactory: ActorRefFactory, zk: ZooKeeperClient, leader: LeadershipAbdication)
    extends LeadershipModule(actorRefFactory, zk, leader) {
  override def startWhenLeader(props: Props, name: String): ActorRef =
    actorRefFactory.actorOf(props, name)
  override def coordinator(): LeadershipCoordinator = ???
}
