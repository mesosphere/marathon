package mesosphere.marathon
package core.election

import akka.stream.scaladsl.{ Keep, Sink, Source }
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ Executors }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.store.impl.zk.NoRetryPolicy
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.stream.EnrichedFlow
import mesosphere.marathon.util.{ LifeCycledCloseable, ScallopStub }
import org.apache.curator.framework.CuratorFrameworkFactory
import org.scalatest.Inside
import org.scalatest.concurrent.Eventually

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

@IntegrationTest
class CuratorElectionStreamTest extends AkkaUnitTest with Inside with ZookeeperServerTest with Eventually {
  val prefixId = new AtomicInteger(0)

  case class Fixture(prefix: String = "curator") {
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

  "CuratorElectionStream.newCuratorConnection" should {
    "throw an exception when given an unresolvable hostname" in {
      val conf = new ZookeeperConf {
        override lazy val zooKeeperUrl = ScallopStub(Some("zk://unresolvable:8080/marathon"))
        override lazy val zooKeeperSessionTimeout = ScallopStub(Some(1000L))
        override lazy val zooKeeperConnectionTimeout = ScallopStub(Some(1000L))
        override lazy val zkSessionTimeoutDuration = 10000.milliseconds
        override lazy val zkConnectionTimeoutDuration = 10000.milliseconds
        override lazy val zkTimeoutDuration = 250.milliseconds
      }

      a[Throwable] shouldBe thrownBy {
        CuratorElectionStream.newCuratorConnection(conf)
      }
    }
  }

  "Yields an event that it is the leader on connection" in withFixture { f =>
    val (cancellable, leader) = CuratorElectionStream(f.client, f.leaderPath, 5000.millis, "host:8080", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run
    leader.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)
    cancellable.cancel()
    leader.pull().futureValue shouldBe Some(LeadershipState.Standby(None))
    leader.pull().futureValue shouldBe None
  }

  "Abdicates leadership immediately when the client is closed" in withFixture { f =>
    // implicit val patienceConfig = PatienceConfig(30.seconds, 10.millis)

    val (cancellable1, leader1) = CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "host:1", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run

    leader1.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)

    val (cancellable2, leader2) = CuratorElectionStream(f.client2, f.leaderPath, 15000.millis, "host:2", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run

    leader2.pull().futureValue shouldBe Some(LeadershipState.Standby(Some("host:1")))

    f.client.close() // simulate a connection close for the first client

    leader2.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)

    cancellable1.cancel()
    cancellable2.cancel()
  }

  "Monitors leadership changes" in withFixture { f =>
    val (cancellable1, leader1) = CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "changehost:1", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run

    leader1.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)

    val (cancellable2, leader2) = CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "changehost:2", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run

    leader2.pull().futureValue shouldBe Some(LeadershipState.Standby(Some("changehost:1")))

    val (cancellable3, leader3) = CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "changehost:3", f.electionEC)
      .toMat(Sink.queue())(Keep.both)
      .run

    leader3.pull().futureValue shouldBe Some(LeadershipState.Standby(Some("changehost:1")))

    cancellable1.cancel()
    leader2.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)
    leader3.pull().futureValue shouldBe Some(LeadershipState.Standby(Some("changehost:2")))
    cancellable2.cancel()
    cancellable3.cancel()
  }

  "It cleans up after itself when the stream completes due to an exception" in withFixture { f =>
    val killSwitch = Promise[Unit]
    val (cancellable, events) = CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "exceptionhost:1", f.electionEC)
      .via(EnrichedFlow.stopOnFirst(Source.fromFuture(killSwitch.future)))
      .toMat(Sink.queue())(Keep.both)
      .run
    eventually { f.client.beforeCloseHooksLength shouldBe 1 }
    events.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)
    killSwitch.success(())
    events.pull().futureValue shouldBe None
    eventually { f.client.beforeCloseHooksLength shouldBe 0 }
  }

  "It fails at least one of the streams if multiple participants register with the same ID" in withFixture { f =>
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
      CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "duplicate-host", f.electionEC)
        .runWith(Sink.last)
    }.take(2)

    val failure = Future.firstCompletedOf(futures.map(_.failed)).futureValue

    inside(failure) {
      case ex: IllegalStateException =>
        ex.getMessage shouldBe "Multiple election participants have the same ID: duplicate-host. This is not allowed."
    }
  }
}
