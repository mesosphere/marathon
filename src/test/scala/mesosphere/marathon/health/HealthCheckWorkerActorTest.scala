package mesosphere.marathon.health

import java.net.{ InetAddress, ServerSocket }

import akka.actor.Props
import akka.testkit.{ ImplicitSender, TestActorRef }
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.scalatest.Matchers

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class HealthCheckWorkerActorTest
    extends MarathonActorSupport
    with ImplicitSender
    with MarathonSpec
    with Matchers {

  import HealthCheckWorker._
  import MarathonTestHelper.Implicits._

  import scala.concurrent.ExecutionContext.Implicits.global

  test("A TCP health check should correctly resolve the hostname") {
    val socket = new ServerSocket(0)
    val socketPort: Int = socket.getLocalPort

    val res = Future {
      socket.accept().close()
    }

    val task =
      MarathonTestHelper.runningTask("test_id")
        .withAgentInfo(_.copy(host = InetAddress.getLocalHost.getCanonicalHostName))
        .withHostPorts(Seq(socketPort))

    val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor]))
    val app = AppDefinition(id = "test_id".toPath)
    ref ! HealthCheckJob(app, task, task.launched.get, HealthCheck(protocol = Protocol.TCP, portIndex = Some(0)))

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

    val task =
      MarathonTestHelper.runningTask("test_id")
        .withAgentInfo(_.copy(host = InetAddress.getLocalHost.getCanonicalHostName))
        .withHostPorts(Seq(socketPort))

    val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor]))
    val app = AppDefinition(id = "test_id".toPath)
    ref ! HealthCheckJob(app, task, task.launched.get, HealthCheck(protocol = Protocol.TCP, portIndex = Some(0)))

    try { Await.result(res, 1.seconds) }
    finally { socket.close() }

    expectMsgPF(1.seconds) {
      case _: HealthResult => ()
    }

    watch(ref)
    expectTerminated(ref)
  }
}
