package mesosphere.marathon.core.election.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import mesosphere.UnitTest
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.base.{AsyncExitExtension, CurrentRuntime, ShutdownHooks}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test.Mockito
import org.rogach.scallop.ScallopOption

import scala.concurrent.duration._
import scala.language.implicitConversions

class CuratorElectionServiceTest extends UnitTest with Mockito {

  def scallopOption[A](a: Option[A]): ScallopOption[A] = {
    new ScallopOption[A]("") {
      override def get = a
      override def apply() = a.get
    }
  }

  "The CuratorElectionService" when {

    val conf: MarathonConf = mock[MarathonConf]
    val system: ActorSystem = mock[ActorSystem]
    val eventStream: EventStream = mock[EventStream]
    val metrics: Metrics = mock[Metrics]
    val hostPort = "80"
    val backoff: ExponentialBackoff = new ExponentialBackoff(0.01.seconds, 0.1.seconds)
    val shutdownHooks: ShutdownHooks = mock[ShutdownHooks]

    // Mock asyncExit call through dependency injection.
    val asyncExitExtension = mock[AsyncExitExtension]
    val runtime: CurrentRuntime = new CurrentRuntime {
      override implicit def extend(runtime: Runtime): AsyncExitExtension = asyncExitExtension
    }

    val service = new CuratorElectionService(conf, system, eventStream, metrics, hostPort, backoff, shutdownHooks)(runtime)

    "given an unresolvable hostname" should {

      conf.zkHosts returns ("unresolvable:8080")
      conf.zooKeeperSessionTimeout returns (scallopOption(Some(10 * 1000L)))

      "shut Marathon down on a NonFatal" in {
        service.offerLeadershipImpl()
        verify(asyncExitExtension, times(1)).asyncExit()(scala.concurrent.ExecutionContext.global)
      }
    }

  }
}
