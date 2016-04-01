package mesosphere.marathon.core.leadership

import akka.actor.{ ActorRef, ActorRefFactory, Props }
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.marathon.LeadershipAbdication
import mesosphere.marathon.core.leadership.impl._

trait LeadershipModule {
  /**
    * Create a wrapper around an actor which should only be active if this instance of Marathon is the
    * current leader instance. The wrapper starts the actor with the given props when appropriate and stops
    * it when this instance loses Leadership. If the wrapper receives messages while not being the leader,
    * it stashes all messages with Status.Failure messages.
    *
    * @param props the props to create the actor
    * @param name the name of the actor (the wrapping actor will be named like this)
    */
  def startWhenLeader(props: Props, name: String): ActorRef

  def coordinator(): LeadershipCoordinator
}

object LeadershipModule {
  def apply(actorRefFactory: ActorRefFactory, zk: ZooKeeperClient, leader: LeadershipAbdication): LeadershipModule = {
    new LeadershipModuleImpl(actorRefFactory, zk, leader)
  }
}

/**
  * This module provides a utility function for starting actors only when our instance is the current leader.
  * This should be used for all normal top-level actors.
  *
  * In addition, it exports the coordinator which coordinates the activity performed when elected or stopped.
  * The leadership election logic needs to call the appropriate methods for this module to work.
  */
private[leadership] class LeadershipModuleImpl(
    actorRefFactory: ActorRefFactory, zk: ZooKeeperClient, leader: LeadershipAbdication) extends LeadershipModule {

  private[this] var whenLeaderRefs = Set.empty[ActorRef]
  private[this] var started: Boolean = false

  override def startWhenLeader(props: Props, name: String): ActorRef = {
    require(!started, s"already started: $name")
    val proxyProps = WhenLeaderActor.props(props)
    val actorRef = actorRefFactory.actorOf(proxyProps, name)
    whenLeaderRefs += actorRef
    actorRef
  }

  override def coordinator(): LeadershipCoordinator = coordinator_

  private[this] lazy val coordinator_ = {
    require(!started, "already started")
    started = true

    val props = LeadershipCoordinatorActor.props(whenLeaderRefs)
    val actorRef = actorRefFactory.actorOf(props, "leaderShipCoordinator")
    new LeadershipCoordinatorDelegate(actorRef)
  }

  /**
    * Register this actor by default.
    */
  startWhenLeader(AbdicateOnConnectionLossActor.props(zk, leader), "AbdicateOnConnectionLoss")
}
