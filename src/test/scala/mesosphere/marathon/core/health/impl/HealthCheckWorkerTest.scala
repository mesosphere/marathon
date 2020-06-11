package mesosphere.marathon
package core.health.impl

import java.net.{InetAddress, ServerSocket}
import java.util.UUID

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.testkit.ImplicitSender
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, PortDefinition, UnreachableStrategy}

import scala.collection.immutable.Seq
import scala.concurrent.{Future, Promise}

class HealthCheckWorkerTest extends AkkaUnitTest with ImplicitSender {

  "HealthCheckWorker" should {
    "A TCP health check should correctly resolve the hostname and return a Healthy result" in {
      val socket = new ServerSocket(0)
      val socketPort: Int = socket.getLocalPort

      val res = Future {
        socket.accept().close()
      }

      val appId = AbsolutePathId(s"/app-with-tcp-health-check-${UUID.randomUUID()}")
      val app = AppDefinition(id = appId, role = "*", portDefinitions = Seq(PortDefinition(0)))
      val hostName = InetAddress.getLocalHost.getCanonicalHostName
      val agentInfo = AgentInfo(host = hostName, agentId = Some("agent"), region = None, zone = None, attributes = Nil)
      val task = {
        val t: Task = TestTaskBuilder.Helper.runningTaskForApp(appId)
        val hostPorts = Seq(socketPort)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts, ipAddresses = Nil)))
      }
      val instance = TestInstanceBuilder.fromTask(task, agentInfo, UnreachableStrategy.default())

      val resF = HealthCheckWorker
        .run(app, instance, healthCheck = MarathonTcpHealthCheck(portIndex = Some(PortReference(0))))(mat.asInstanceOf[ActorMaterializer])

      try { res.futureValue }
      finally { socket.close() }

      resF.futureValue shouldBe a[Healthy]
    }

    "A HTTP health check should work as expected" in {

      import akka.http.scaladsl.server.Directives._

      val promise = Promise[String]()

      val route =
        path("health") {
          get {
            promise.success("success")
            complete(StatusCodes.OK)
          }
        } ~
          path("unhealthy") {
            get {
              complete(StatusCodes.InternalServerError)
            }
          }

      val binding = Http().bindAndHandle(route, "localhost", 0).futureValue

      val port = binding.localAddress.getPort

      val hostName = "localhost"
      val appId = AbsolutePathId(s"/app-with-http-health-check-${UUID.randomUUID()}")
      val app = AppDefinition(id = appId, role = "*", portDefinitions = Seq(PortDefinition(0)))
      val agentInfo = AgentInfo(host = hostName, agentId = Some("agent"), region = None, zone = None, attributes = Nil)
      val task = {
        val t: Task = TestTaskBuilder.Helper.runningTaskForApp(appId)
        val hostPorts = Seq(port)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts, ipAddresses = Nil)))
      }
      val since = task.status.startedAt.getOrElse(task.status.stagedAt)
      val unreachableStrategy = UnreachableStrategy.default()
      val tasksMap = Map(task.taskId -> task)
      val state = Instance.InstanceState.transitionTo(None, tasksMap, since, unreachableStrategy, Goal.Running)

      val instance = Instance(task.taskId.instanceId, Some(agentInfo), state, tasksMap, app, None, "*")

      val resF = HealthCheckWorker.run(app, instance, healthCheck = MarathonHttpHealthCheck(port = Some(port), path = Some("/health")))(
        mat.asInstanceOf[ActorMaterializer]
      )

      resF.futureValue shouldBe a[Healthy]
      promise.future.futureValue shouldEqual "success"

      val unhealthyResF =
        HealthCheckWorker.run(app, instance, healthCheck = MarathonHttpHealthCheck(port = Some(port), path = Some("/unhealthy")))(
          mat.asInstanceOf[ActorMaterializer]
        )

      unhealthyResF.futureValue shouldBe a[Unhealthy]
    }
  }
}
