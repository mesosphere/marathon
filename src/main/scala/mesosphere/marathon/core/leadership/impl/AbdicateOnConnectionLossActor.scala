package mesosphere.marathon.core.leadership.impl

import akka.actor.{ Actor, ActorLogging, Props }
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.marathon.LeadershipAbdication
import org.apache.zookeeper.ZooKeeper.States
import org.apache.zookeeper.{ ZooKeeper, WatchedEvent, Watcher }
import AbdicateOnConnectionLossActor._

private[leadership] object AbdicateOnConnectionLossActor {
  def props(zk: ZooKeeperClient, leader: LeadershipAbdication): Props = {
    Props(new AbdicateOnConnectionLossActor(zk, leader))
  }

  private val connectionDropped = Set(
    Watcher.Event.KeeperState.Disconnected,
    Watcher.Event.KeeperState.Expired
  )
}

/**
  * Register as ZK Listener and abdicates leadership on connection loss.
  */
private[impl] class AbdicateOnConnectionLossActor(zk: ZooKeeperClient,
                                                  leader: LeadershipAbdication) extends Actor with ActorLogging {

  private[impl] val watcher = new Watcher {
    val reference = self
    override def process(event: WatchedEvent): Unit = reference ! event
  }

  override def preStart(): Unit = {
    log.info("Register as ZK Listener")
    zk.register(watcher)
    //make sure, we are connected so we can act on subsequent events
    if (zk.get().getState != ZooKeeper.States.CONNECTED) leader.abdicateLeadership()
  }
  override def postStop(): Unit = {
    log.info("Unregister as ZK Listener")
    zk.unregister(watcher)
  }

  def disconnected(event: WatchedEvent): Unit = {
    log.warning(s"ZooKeeper connection has been dropped. Abdicate Leadership: $event")
    leader.abdicateLeadership()
  }

  override def receive: Receive = {
    case event: WatchedEvent if connectionDropped.contains(event.getState) => disconnected(event)
    case event: WatchedEvent => log.info(s"Received ZooKeeper Status event: $event")
  }
}
