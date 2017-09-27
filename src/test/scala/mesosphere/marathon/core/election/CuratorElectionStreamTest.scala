package mesosphere.marathon
package core.election

import akka.stream.scaladsl.{ Keep, Sink, Source }
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ Executors }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.stream.EnrichedFlow
import mesosphere.marathon.util.{ LifeCycledCloseableLike, ScallopStub }
import org.apache.curator.framework.CuratorFramework
import org.scalatest.Inside
import org.scalatest.concurrent.Eventually
import scala.concurrent.Promise

import scala.concurrent.duration._
import scala.util.{ Failure, Try }

@IntegrationTest
class CuratorElectionStreamTest extends AkkaUnitTest with Inside with ZookeeperServerTest with Eventually {
  val underlyingZkClient = zkClient().client
  val prefixId = new AtomicInteger(0)

  case class Fixture(prefix: String = "curator") {
    val leaderPath = s"/curator-${prefixId.getAndIncrement}"
    private def newStubClient =
      new StubLifeCycledCloseable(underlyingZkClient)
    val client = newStubClient
    val client2 = newStubClient
    val executorService = Executors.newSingleThreadExecutor()
  }

  def withFixture(fn: Fixture => Unit): Unit = {
    val f = Fixture()
    try fn(f)
    finally {
      f.client.close()
      f.client2.close()
      f.executorService.shutdown()
    }
  }

  /**
    * Doesn't actually close the underlying object
    */
  class StubLifeCycledCloseable(val closeable: CuratorFramework) extends LifeCycledCloseableLike[CuratorFramework] {
    var beforeCloseHooks = List.empty[() => Unit]
    override def close(): Unit = synchronized {
      beforeCloseHooks.foreach { hook =>
        Try(hook())
      }
      beforeCloseHooks = Nil
    }

    override def beforeClose(fn: () => Unit): Unit = synchronized {
      beforeCloseHooks = fn :: beforeCloseHooks
    }

    override def removeBeforeClose(fn: () => Unit): Unit = synchronized {
      beforeCloseHooks = beforeCloseHooks.filterNot(_ == fn)
    }
  }

  "CuratorElectionStream.newCuratorConnection" should {
    "throw an excepgtion when given an unresolvable hostname" in {
      val conf = new ZookeeperConf {
        override lazy val zooKeeperUrl = ScallopStub(Some("zk://unresolvable:8080/marathon"))
        override lazy val zooKeeperSessionTimeout = ScallopStub(Some(1000L))
        override lazy val zooKeeperConnectionTimeout = ScallopStub(Some(1000L))
        override lazy val zkSessionTimeoutDuration = 10000.milliseconds
        override lazy val zkConnectionTimeoutDuration = 10000.milliseconds
        override lazy val zkTimeoutDuration = 250.milliseconds
      }

      inside(Try(CuratorElectionStream.newCuratorConnection(conf))) {
        case Failure(ex) =>
          ex shouldBe a[UnknownHostException]
      }
    }
  }

  "Yields an event that it is the leader on connection" in withFixture { f =>
    val (cancellable, leader) = CuratorElectionStream(f.client, f.leaderPath, 5000.millis, "host:8080", f.executorService)
      .toMat(Sink.queue())(Keep.both)
      .run
    leader.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)
    cancellable.cancel()
    leader.pull().futureValue shouldBe Some(LeadershipState.Standby(None))
    leader.pull().futureValue shouldBe None
  }

  "Abdicates leadership immediately when the client is closed" in withFixture { f =>
    // implicit val patienceConfig = PatienceConfig(30.seconds, 10.millis)

    val (cancellable1, leader1) = CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "host:1", f.executorService)
      .toMat(Sink.queue())(Keep.both)
      .run

    leader1.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)

    val (cancellable2, leader2) = CuratorElectionStream(f.client2, f.leaderPath, 15000.millis, "host:2", f.executorService)
      .toMat(Sink.queue())(Keep.both)
      .run

    leader2.pull().futureValue shouldBe Some(LeadershipState.Standby(Some("host:1")))

    f.client.close() // simulate a connection close for the first client

    leader2.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)

    cancellable1.cancel()
    cancellable2.cancel()
  }

  "Monitors leadership changes" in withFixture { f =>
    val (cancellable1, leader1) = CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "changehost:1", f.executorService)
      .toMat(Sink.queue())(Keep.both)
      .run

    leader1.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)

    val (cancellable2, leader2) = CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "changehost:2", f.executorService)
      .toMat(Sink.queue())(Keep.both)
      .run

    leader2.pull().futureValue shouldBe Some(LeadershipState.Standby(Some("changehost:1")))

    val (cancellable3, leader3) = CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "changehost:3", f.executorService)
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
    val (cancellable, events) = CuratorElectionStream(f.client, f.leaderPath, 15000.millis, "changehost:1", f.executorService)
      .via(EnrichedFlow.stopOnFirst(Source.fromFuture(killSwitch.future)))
      .toMat(Sink.queue())(Keep.both)
      .run
    eventually { f.client.beforeCloseHooks.length shouldBe 1 }
    events.pull().futureValue shouldBe Some(LeadershipState.ElectedAsLeader)
    killSwitch.success(())
    events.pull().futureValue shouldBe None
    eventually { f.client.beforeCloseHooks.length shouldBe 0 }
  }
}
