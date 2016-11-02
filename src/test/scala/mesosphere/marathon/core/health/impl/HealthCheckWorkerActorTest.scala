package mesosphere.marathon
package core.health.impl

import java.net.{ InetAddress, ServerSocket }

import akka.actor.Props
import akka.testkit.{ ImplicitSender, TestActorRef }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.health.{ HealthResult, Healthy, MarathonTcpHealthCheck, PortReference }
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{ LegacyAppInstance, TestTaskBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{ AppDefinition, PathId, PortDefinition, UnreachableStrategy }

import scala.collection.immutable.Seq
import scala.concurrent.Future

class HealthCheckWorkerActorTest extends AkkaUnitTest with ImplicitSender {

  import HealthCheckWorker._

  "HealthCheckWorkerActor" should {
    "A TCP health check should correctly resolve the hostname" in {
      val socket = new ServerSocket(0)
      val socketPort: Int = socket.getLocalPort

      val res = Future {
        socket.accept().close()
      }

      val appId = PathId("/test_id")
      val app = AppDefinition(id = appId, portDefinitions = Seq(PortDefinition(0)))
      val hostName = InetAddress.getLocalHost.getCanonicalHostName
      val agentInfo = AgentInfo(host = hostName, agentId = Some("agent"), attributes = Nil)
      val task = {
        val t: Task.LaunchedEphemeral = TestTaskBuilder.Helper.runningTaskForApp(appId)
        val hostPorts = Seq(socketPort)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts, ipAddresses = Nil)))
      }
      val instance = LegacyAppInstance(task, agentInfo, UnreachableStrategy.default())

      val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor], mat))
      ref ! HealthCheckJob(app, instance, MarathonTcpHealthCheck(portIndex = Some(PortReference(0))))

      try { res.futureValue }
      finally { socket.close() }

      expectMsgPF(patienceConfig.timeout) {
        case Healthy(taskId, _, _, _) => ()
      }
    }

    "A health check worker should shut itself down" in {
      val socket = new ServerSocket(0)
      val socketPort: Int = socket.getLocalPort

      val res = Future {
        socket.accept().close()
      }

      val appId = PathId("/test_id")
      val app = AppDefinition(id = appId, portDefinitions = Seq(PortDefinition(0)))
      val hostName = InetAddress.getLocalHost.getCanonicalHostName
      val agentInfo = AgentInfo(host = hostName, agentId = Some("agent"), attributes = Nil)
      val task = {
        val t: Task.LaunchedEphemeral = TestTaskBuilder.Helper.runningTaskForApp(appId)
        val hostPorts = Seq(socketPort)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts, ipAddresses = Nil)))
      }
      val instance = LegacyAppInstance(task, agentInfo, UnreachableStrategy.default())

      val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor], mat))
      ref ! HealthCheckJob(app, instance, MarathonTcpHealthCheck(portIndex = Some(PortReference(0))))

      try { res.futureValue }
      finally { socket.close() }

      expectMsgPF(patienceConfig.timeout) {
        case _: HealthResult => ()
      }

      watch(ref)
      expectTerminated(ref)
    }
  }
}
