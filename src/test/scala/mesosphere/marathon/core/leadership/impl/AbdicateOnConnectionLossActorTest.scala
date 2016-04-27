package mesosphere.marathon.core.leadership.impl

import akka.actor.ActorSystem
import akka.testkit.{ TestActorRef, TestKit }
import com.twitter.common.zookeeper.ZooKeeperClient
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.MarathonSpec
import org.apache.zookeeper.{ ZooKeeper, WatchedEvent, Watcher }
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, GivenWhenThen }

class AbdicateOnConnectionLossActorTest
    extends MarathonActorSupport with MarathonSpec with Mockito with GivenWhenThen with BeforeAndAfter {

  test("register as zk listener on start") {
    Given("ZK and leader refs")
    val electionService = mock[ElectionService]

    When("The actor is created")
    val actor = TestActorRef(AbdicateOnConnectionLossActor.props(zk, electionService))

    Then("register is called")
    verify(zk).register(any)
  }

  test("zk disconnect events lead to abdication") {
    Given("A started AbdicateOnConnectionLossActor")
    val electionService = mock[ElectionService]
    val actor = TestActorRef[AbdicateOnConnectionLossActor](AbdicateOnConnectionLossActor.props(zk, electionService))

    When("The actor is killed")
    val disconnected = new WatchedEvent(Watcher.Event.EventType.None, Watcher.Event.KeeperState.Disconnected, "")
    actor.underlyingActor.watcher.process(disconnected)

    Then("Abdication is called")
    verify(electionService).abdicateLeadership()
  }

  test("other zk events do not lead to abdication") {
    Given("A started AbdicateOnConnectionLossActor")
    val electionService = mock[ElectionService]
    val actor = TestActorRef[AbdicateOnConnectionLossActor](AbdicateOnConnectionLossActor.props(zk, electionService))

    When("An event is fired, that is not a disconnected event")
    val authFailed = new WatchedEvent(Watcher.Event.EventType.None, Watcher.Event.KeeperState.AuthFailed, "")
    actor.underlyingActor.watcher.process(authFailed)

    Then("Abdication is _NOT_ called")
    verify(electionService, never).abdicateLeadership()
  }

  var zk: ZooKeeperClient = _

  before {
    val zookeeper = mock[ZooKeeper]
    zookeeper.getState returns ZooKeeper.States.CONNECTED
    zk = mock[ZooKeeperClient]
    zk.get() returns zookeeper
  }
}
