package mesosphere.marathon.health

import java.net.{ ServerSocket, InetAddress }

import akka.actor.{ Props, ActorSystem }
import akka.testkit.{ ImplicitSender, TestActorRef, TestKit }
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.{ Protos, MarathonSpec }
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class HealthCheckWorkerActorTest
    extends TestKit(ActorSystem("System"))
    with ImplicitSender
    with MarathonSpec
    with Matchers
    with BeforeAndAfterAll {

  import mesosphere.util.ThreadPoolContext.context
  import HealthCheckWorker._

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("A TCP health check should correctly resolve the hostname") {
    val socket = new ServerSocket(0)
    val socketPort: Int = socket.getLocalPort

    val res = Future {
      socket.accept().close()
    }

    val task = Protos.MarathonTask
      .newBuilder
      .setHost(InetAddress.getLocalHost.getCanonicalHostName)
      .setId("test_id")
      .addPorts(socketPort)
      .build()

    val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor]))

    ref ! HealthCheckJob(task, HealthCheck(protocol = Protocol.TCP))

    try { Await.result(res, 1.seconds) }
    finally { socket.close() }

    expectMsgPF(1.seconds) {
      case Healthy(taskId, _, _) => ()
    }
  }

  test("A health check worker should shut itself down") {
    val socket = new ServerSocket(0)
    val socketPort: Int = socket.getLocalPort

    val res = Future {
      socket.accept().close()
    }

    val task = Protos.MarathonTask
      .newBuilder
      .setHost(InetAddress.getLocalHost.getCanonicalHostName)
      .setId("test_id")
      .addPorts(socketPort)
      .build()

    val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor]))

    ref ! HealthCheckJob(task, HealthCheck(protocol = Protocol.TCP))

    try { Await.result(res, 1.seconds) }
    finally { socket.close() }

    expectMsgPF(1.seconds) {
      case _: HealthResult => ()
    }

    watch(ref)
    expectTerminated(ref)
  }

}
