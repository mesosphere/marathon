package mesosphere.marathon.health

import java.net.{ ServerSocket, InetAddress }

import akka.actor.{ Props, ActorSystem }
import akka.testkit.{ TestActorRef, TestKit }
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.{ Protos, MarathonSpec }
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class HealthCheckWorkerActorTest
    extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll {

  import mesosphere.util.ThreadPoolContext.context

  test("A TCP health check should correctly resolve the hostname") {
    val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor]))
    val task = Protos.MarathonTask
      .newBuilder
      .setHost(InetAddress.getLocalHost.getCanonicalHostName)
      .setId("test_id")
      .build()

    val socket = new ServerSocket(0)

    val res = Future {
      socket.accept().close()
    }

    ref.underlyingActor.tcp(task, HealthCheck(protocol = Protocol.TCP), socket.getLocalPort)

    try {
      Await.result(res, 1.seconds)
    }
    finally {
      socket.close()
    }
  }
}
