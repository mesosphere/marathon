package mesosphere.marathon.core.health.impl

import java.net.{ InetAddress, ServerSocket }

import akka.actor.Props
import akka.testkit.{ ImplicitSender, TestActorRef }
import mesosphere.marathon.core.health.{ HealthResult, Healthy, MarathonTcpHealthCheck, PortReference }
import mesosphere.marathon.core.instance.TestTaskBuilder
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
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
  import scala.concurrent.ExecutionContext.Implicits.global

  test("A TCP health check should correctly resolve the hostname") {
    val socket = new ServerSocket(0)
    val socketPort: Int = socket.getLocalPort

    val res = Future {
      socket.accept().close()
    }

    val appId = PathId("/test_id")
    val app = AppDefinition(id = appId)
    val task = {
      val t: Task.LaunchedEphemeral = TestTaskBuilder.Helper.runningTaskForApp(appId)
      val hostName = InetAddress.getLocalHost.getCanonicalHostName
      val hostPorts = Seq(socketPort)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts, ipAddresses = None)))
    }

    val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor]))
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
    val app = AppDefinition(id = appId)
    val task = {
      val t: Task.LaunchedEphemeral = TestTaskBuilder.Helper.runningTaskForApp(appId)
      val hostName = InetAddress.getLocalHost.getCanonicalHostName
      val hostPorts = Seq(socketPort)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts, ipAddresses = None)))
    }

    val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor]))
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
