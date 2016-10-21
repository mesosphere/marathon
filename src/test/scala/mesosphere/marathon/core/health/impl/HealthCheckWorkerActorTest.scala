package mesosphere.marathon.core.health.impl

import java.net.{ InetAddress, ServerSocket }

import akka.actor.Props
import akka.testkit.{ ImplicitSender, TestActorRef }
import mesosphere.marathon.core.health.{ HealthResult, Healthy, MarathonTcpHealthCheck, PortReference }
import mesosphere.marathon.core.instance.TestTaskBuilder
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.test.{ MarathonActorSupport, MarathonSpec }
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
  import mesosphere.marathon.test.MarathonTestHelper.Implicits._

  import scala.concurrent.ExecutionContext.Implicits.global

  test("A TCP health check should correctly resolve the hostname") {
    val appId = PathId("/test_id")
    val socket = new ServerSocket(0)
    val socketPort: Int = socket.getLocalPort

    val res = Future {
      socket.accept().close()
    }

    val task =
      TestTaskBuilder.Helper.runningTaskForApp(appId)
        .withAgentInfo(_.copy(host = InetAddress.getLocalHost.getCanonicalHostName))
        .withHostPorts(Seq(socketPort))

    val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor]))
    val app = AppDefinition(id = appId)
    ref ! HealthCheckJob(app, task, MarathonTcpHealthCheck(portIndex = Some(PortReference(0))))

    try { Await.result(res, 1.seconds) }
    finally { socket.close() }

    expectMsgPF(1.seconds) {
      case Healthy(taskId, _, _, _) => ()
    }
  }

  test("A health check worker should shut itself down") {
    val socket = new ServerSocket(0)
    val socketPort: Int = socket.getLocalPort

    val res = Future {
      socket.accept().close()
    }

    val appId = PathId("/test_id")
    val task =
      TestTaskBuilder.Helper.runningTaskForApp(appId)
        .withAgentInfo(_.copy(host = InetAddress.getLocalHost.getCanonicalHostName))
        .withHostPorts(Seq(socketPort))

    val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor]))
    val app = AppDefinition(id = appId)
    ref ! HealthCheckJob(app, task, MarathonTcpHealthCheck(portIndex = Some(PortReference(0))))

    try { Await.result(res, 1.seconds) }
    finally { socket.close() }

    expectMsgPF(1.seconds) {
      case _: HealthResult => ()
    }

    watch(ref)
    expectTerminated(ref)
  }
}
