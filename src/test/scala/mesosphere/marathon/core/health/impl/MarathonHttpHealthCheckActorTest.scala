package mesosphere.marathon
package core.health.impl

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.TestProbe
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition.Running
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.health.impl.AppHealthCheckActor.{ HealthCheckStatusChanged, PurgeHealthCheckStatuses }
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{ Instance, TestTaskBuilder }
import mesosphere.marathon.core.task.KillServiceMock
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ AppDefinition, PathId, PortDefinition, UnreachableStrategy }
import org.scalatest.Inside

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class MarathonHttpHealthCheckActorTest extends AkkaUnitTest with Inside {

  class Fixture {
    import akka.http.scaladsl.server.Directives._
    val healthPromise = Promise[String]()

    val route =
      path("health") {
        get {
          healthPromise.trySuccess("success")
          complete(StatusCodes.OK)
        }
      } ~
        path("unhealthy"){
          get {
            healthPromise.trySuccess("failure")
            complete(StatusCodes.InternalServerError)
          }
        }

    val binding = Http().bindAndHandle(route, "localhost", 0).futureValue
    val port = binding.localAddress.getPort

    val hostName = "localhost"
    val appId = PathId("/app-for-http-health-checking")
    val app = AppDefinition(id = appId, portDefinitions = Seq(PortDefinition(0)))
    val agentInfo = AgentInfo(host = hostName, agentId = Some("agent"), attributes = Nil)
    val task = {
      val t = TestTaskBuilder.Helper.runningTaskForApp(appId)
      t.copy(status = t.status.copy(condition = Running, networkInfo = NetworkInfo(hostName, Seq(port), ipAddresses = Nil)))
    }

    val since = task.status.startedAt.getOrElse(task.status.stagedAt)
    val state = Instance.InstanceState(None, Map(task.taskId -> task), since, UnreachableStrategy.default())
    val instance = Instance(task.taskId.instanceId, agentInfo, state, Map(task.taskId -> task), app.version, UnreachableStrategy.default())

    val appHealthCheckActor = TestProbe()
    val instanceKiller = new KillServiceMock(system)
    val instanceTracker = mock[InstanceTracker]

    instanceTracker.specInstances(app.id) returns Future.successful(Seq(instance))
    instanceTracker.instance(instance.instanceId) returns Future.successful(Some(instance))
  }

  def withFixture[T](testCode: Fixture => T): T = {
    val f = new Fixture()
    try {
      testCode(f)
    } finally {
      f.binding.unbind().futureValue
    }
  }

  "A healthy HTTP health check should work as expected" in new Fixture {
    When("MarathonHttpHealthCheckActor is started for an app which instance is healthy")
    system.actorOf(MarathonHttpHealthCheckActor.props(
      app,
      appHealthCheckActor.ref,
      instanceKiller,
      MarathonHttpHealthCheck(port = Some(port), path = Some("/health"), interval = 1.second),
      instanceTracker,
      system.eventStream
    ))

    Then("fake task should have been pinged and the ping succeeded")
    healthPromise.future.futureValue shouldBe "success"

    And("AppHealthCheckActor received purge message")
    appHealthCheckActor.expectMsgClass(classOf[PurgeHealthCheckStatuses])

    And("AppHealthCheckActor received a health change message with a healthy state")
    val healthStatus = appHealthCheckActor.receiveOne(5.seconds)
    inside(healthStatus){
      case HealthCheckStatusChanged(_, _, healthState) =>
        healthState.alive shouldBe true
    }

  }

  "An unhealthy HTTP health check should work as expected" in new Fixture {
    When("MarathonHttpHealthCheckActor is started for an app which instance is unhealthy")
    system.actorOf(MarathonHttpHealthCheckActor.props(
      app,
      appHealthCheckActor.ref,
      instanceKiller,
      MarathonHttpHealthCheck(port = Some(port), path = Some("/unhealthy"), interval = 1.second, gracePeriod = 0.seconds, maxConsecutiveFailures = 0),
      instanceTracker,
      system.eventStream
    ))

    Then("fake task should have been pinged and the ping failed")
    healthPromise.future.futureValue shouldBe "failure"

    And("AppHealthCheckActor received purge message")
    appHealthCheckActor.expectMsgClass(classOf[PurgeHealthCheckStatuses])

    And("AppHealthCheckActor received a health change message with an unhealthy state")
    val healthStatus = appHealthCheckActor.receiveOne(5.seconds)
    inside(healthStatus){
      case HealthCheckStatusChanged(_, _, healthState) =>
        healthState.alive shouldBe false
    }
  }
}
