package mesosphere.marathon
package core.election

import akka.stream.scaladsl.{Keep, Sink, SinkQueue, Source}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.base.LifecycleState
import mesosphere.marathon.core.storage.store.impl.zk.NoRetryPolicy
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.storage.StorageConfig
import mesosphere.marathon.stream.EnrichedFlow
import mesosphere.marathon.test.ThrowExceptionAndDontCrashStrategy
import mesosphere.marathon.util.{LifeCycledCloseable, ZookeeperServerTest}
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.recipes.leader.LeaderLatch
import org.rogach.scallop.ScallopConf
import org.scalatest.Inside
import org.scalatest.concurrent.Eventually

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

class CuratorElectionStreamTest extends AkkaUnitTest with Inside with ZookeeperServerTest with Eventually {
  val prefixId = new AtomicInteger(0)

  case class Fixture(prefix: String = "curator") {
    val metrics = DummyMetrics
    val leaderPath = s"/curator-${prefixId.getAndIncrement}"
    def newClient() = {
      val c = CuratorFrameworkFactory.newClient(zkServer.connectUri, NoRetryPolicy)
      c.start()
      c.blockUntilConnected()
      c
    }

    val client = new LifeCycledCloseable(newClient())
    val client2 = new LifeCycledCloseable(newClient())
    val electionExecutor = Executors.newSingleThreadExecutor()
    val electionEC = ExecutionContext.fromExecutor(electionExecutor)
    lazy val dummyLatch = new LeaderLatch(client.closeable, leaderPath + "-curator", "dummy")
  }

  def withFixture(fn: Fixture => Unit): Unit = {
    val f = Fixture()
    try fn(f)
    finally {
      f.client.close()
      f.client2.close()
      f.electionExecutor.shutdown()
    }
  }

  private def nextKnownState(sinkQueue: SinkQueue[LeadershipState]): Option[LeadershipState] = {
    val next = sinkQueue.pull().futureValue
    if (next == Some(LeadershipState.Standby(None)))
      nextKnownState(sinkQueue)
    else
      next
  }

  "CuratorElectionStream.newCuratorConnection" should {
    "throw an exception when given an unresolvable hostname" in {
      val conf = new ScallopConf(args = List("--zk", "zk://unresolvable:8080/marathon/leader")) with ZookeeperConf
      conf.verify()

      a[Throwable] shouldBe thrownBy {
        StorageConfig.curatorFramework(conf, ThrowExceptionAndDontCrashStrategy, LifecycleState.Ignore).client
      }
    }
  }

  "Yields an event that it is the leader on connection" in withFixture { f =>
    val (cancellable, leader) = CuratorElectionStream(
      f.metrics, f.client, f.leaderPath, 5000.millis, "host:8080", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run
    nextKnownState(leader) shouldBe Some(LeadershipState.ElectedAsLeader)
    cancellable.cancel()
    leader.pull().futureValue shouldBe Some(LeadershipState.Standby(None))
    leader.pull().futureValue shouldBe None
  }

  "Abdicates leadership immediately when the client is closed" in withFixture { f =>
    // implicit val patienceConfig = PatienceConfig(30.seconds, 10.millis)

    val (cancellable1, leader1) = CuratorElectionStream(
      f.metrics, f.client, f.leaderPath, 15000.millis, "host:1", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run

    nextKnownState(leader1) shouldBe Some(LeadershipState.ElectedAsLeader)

    val (cancellable2, leader2) = CuratorElectionStream(
      f.metrics, f.client2, f.leaderPath, 15000.millis, "host:2", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run

    nextKnownState(leader2) shouldBe Some(LeadershipState.Standby(Some("host:1")))

    f.client.close() // simulate a connection close for the first client

    nextKnownState(leader2) shouldBe Some(LeadershipState.ElectedAsLeader)

    cancellable1.cancel()
    cancellable2.cancel()
  }

  "Monitors leadership changes" in withFixture { f =>
    val (cancellable1, leader1) = CuratorElectionStream(
      f.metrics, f.client, f.leaderPath, 15000.millis, "changehost:1", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run

    nextKnownState(leader1) shouldBe Some(LeadershipState.ElectedAsLeader)

    val (cancellable2, leader2) = CuratorElectionStream(
      f.metrics, f.client, f.leaderPath, 15000.millis, "changehost:2", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run

    nextKnownState(leader2) shouldBe Some(LeadershipState.Standby(Some("changehost:1")))

    eventually { f.dummyLatch.getParticipants.size() shouldBe 2 } // wait for leader2 to register its leadership record

    val (cancellable3, leader3) = CuratorElectionStream(
      f.metrics, f.client, f.leaderPath, 15000.millis, "changehost:3", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run

    nextKnownState(leader3) shouldBe Some(LeadershipState.Standby(Some("changehost:1")))

    cancellable1.cancel()
    nextKnownState(leader2) shouldBe Some(LeadershipState.ElectedAsLeader)
    nextKnownState(leader3) shouldBe Some(LeadershipState.Standby(Some("changehost:2")))
    cancellable2.cancel()
    cancellable3.cancel()
  }

  "It cleans up after itself when the stream completes due to an exception" in withFixture { f =>
    val killSwitch = Promise[Unit]
    val (cancellable, events) = CuratorElectionStream(
      f.metrics, f.client, f.leaderPath, 15000.millis, "exceptionhost:1", f.electionEC)
      .via(EnrichedFlow.stopOnFirst(Source.fromFuture(killSwitch.future)))
      .toMat(Sink.queue())(Keep.both)
      .run
    eventually { f.client.beforeCloseHooksLength shouldBe 1 }
    nextKnownState(events) shouldBe Some(LeadershipState.ElectedAsLeader)
    killSwitch.success(())
    events.pull().futureValue shouldBe None
    eventually { f.client.beforeCloseHooksLength shouldBe 0 }
  }

  "It fails at least one of the streams if multiple participants register with the same id" in withFixture { f =>
    /*
     * It's not possible to predict which of the streams will crash; it's inherently racy. Participant 2 could connect,
     * detect the duplicate, crash, and remove its leader record before the participant 1 has a chance to see it.
     *
     * Conversely, participant 2 could connect, and already connected participant 1 could spot the illegal state and
     * remove its own participant record before participant 2 first sees any of the participant records.
     *
     * Or, both could see spot the illegal state, and both could crash.
     */
    val futures = Stream.continually {
      CuratorElectionStream(f.metrics, f.client, f.leaderPath, 15000.millis, "duplicate-host", f.electionEC)
        .runWith(Sink.last)
    }.take(2)

    val failure = Future.firstCompletedOf(futures.map(_.failed)).futureValue

    inside(failure) {
      case ex: IllegalStateException =>
        ex.getMessage shouldBe "Multiple election participants have the same id: duplicate-host. This is not allowed."
    }
  }

  "CuratorElectionStream quickly emits uncertainty about current leader during connection troubles" in withFixture { f =>
    val (cancellable, leader) = CuratorElectionStream(
      f.metrics, f.client, f.leaderPath, 5000.millis, "host:8080", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run
    Given("an elected leader")
    nextKnownState(leader) shouldBe Some(LeadershipState.ElectedAsLeader)

    When("we stop the Zookeeper server")
    zkServer.stop()
    val serverStopped = System.currentTimeMillis()

    Then("The stream should emit uncertainty about leadership within 5 seconds")
    leader.pull().futureValue shouldBe Some(LeadershipState.Standby(None))
    val uncertaintyDetermined = System.currentTimeMillis()
    (uncertaintyDetermined - serverStopped).millis should be < 5.seconds

    When("we start the Zookeeper server again")
    zkServer.start()

    Then("The stream should emit the current leadership state again")
    nextKnownState(leader) shouldBe Some(LeadershipState.ElectedAsLeader)

    cancellable.cancel()
    leader.pull().futureValue shouldBe Some(LeadershipState.Standby(None))
    leader.pull().futureValue shouldBe None
  }
}
