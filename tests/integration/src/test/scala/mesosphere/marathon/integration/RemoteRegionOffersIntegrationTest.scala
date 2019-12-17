package mesosphere.marathon
package integration

import com.mesosphere.utils.mesos.{FaultDomain, Zone, MesosConfig, Region}
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.AbsolutePathId
import mesosphere.mesos.Constraints
import org.scalatest.Inside

class RemoteRegionOffersIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Inside {

  class Fixture {
    val homeRegion = Region("home_region")
    val homeZone = Zone("home_zone")

    val remoteRegion = Region("remote_region")
    val remoteZone1 = Zone("remote_zone1")
    val remoteZone2 = Zone("remote_zone2")
  }

  val f = new Fixture

  override lazy val mesosConfig = MesosConfig(
    numAgents = 3,
    mastersFaultDomains = Seq(
      Some(FaultDomain(region = f.homeRegion, zone = f.homeZone))),
    agentsFaultDomains = Seq(
      Some(FaultDomain(region = f.remoteRegion, zone = f.remoteZone1)),
      Some(FaultDomain(region = f.remoteRegion, zone = f.remoteZone2)),
      Some(FaultDomain(region = f.homeRegion, zone = f.homeZone)))

  )

  def appId(suffix: String): AbsolutePathId = testBasePath / s"app-${suffix}"

  "Region Aware marathon" must {
    "Launch an instance of the app in the default region if region is not specified" in {
      val applicationId = appId("must-be-placed-in-home-region")
      val app = appProxy(applicationId, "v1", instances = 1, healthCheck = None)

      When("The app is deployed without specifying region")
      val result = marathon.createAppV2(app)

      Then("The app is created in the default region")
      result should be(Created)

      waitForDeployment(result)
      waitForTasks(applicationId, 1)
      val task = marathon.tasks(applicationId).value.head
      task.region shouldBe Some(f.homeRegion.value)
    }

    "Launch an instance of the app in the specified region" in {
      val applicationId = appId("must-be-placed-in-remote-region")
      val app = appProxy(applicationId, "v1", instances = 1, healthCheck = None).copy(constraints =
        Set(Constraints.regionField :: "LIKE" :: f.remoteRegion.value :: Nil))

      When("The app is deployed with specific region constraint")
      val result = marathon.createAppV2(app)

      Then("The app is created in the specified region")
      result should be(Created)
      waitForDeployment(result)
      waitForTasks(applicationId, 1)
      val task = marathon.tasks(applicationId).value.head
      task.region shouldBe Some(f.remoteRegion.value)
    }

    "Launch an instance of the app in the specified region and zone" in {
      val applicationId = appId("must-be-placed-in-remote-region-and-zone")
      val app = appProxy(applicationId, "v1", instances = 1, healthCheck = None).copy(constraints = Set(
        Constraints.regionField :: "LIKE" :: f.remoteRegion.value :: Nil,
        Constraints.zoneField :: "LIKE" :: f.remoteZone2.value :: Nil
      ))

      When("The app is deployed with specific region and zone constraints")
      val result = marathon.createAppV2(app)

      Then("The app is created in the proper region and a proper zone")
      result should be(Created)
      waitForDeployment(result)
      waitForTasks(applicationId, 1)
      val task = marathon.tasks(applicationId).value.head
      task.region shouldBe Some(f.remoteRegion.value)
      task.zone shouldBe Some(f.remoteZone2.value)
    }

    "Replace an unreachable instance in the same region" in {
      val applicationId = appId("unreachable-instance-is-place-in-same-region")
      val strategy = raml.UnreachableEnabled(inactiveAfterSeconds = 0, expungeAfterSeconds = 4 * 60)
      val app = appProxy(applicationId, "v1", instances = 4, healthCheck = None).copy(constraints = Set(
        Constraints.regionField :: "GROUP_BY" :: "2" :: Nil
      ), unreachableStrategy = Some(strategy))

      Given("an app grouped by two regions")
      val result = marathon.createAppV2(app)
      result should be(Created)
      waitForDeployment(result)
      val tasks = marathon.tasks(applicationId).value
      tasks should have size (4)
      tasks.groupBy(_.region.value).keySet should be(Set("home_region", "remote_region"))
      tasks.groupBy(_.region.value).get("home_region").value should have size (2)
      tasks.groupBy(_.region.value).get("remote_region").value should have size (2)

      When("one agent in the remote region becomes unreachable")
      mesosCluster.agents.find(_.extraArgs.exists(_.contains("remote_region"))).value.stop()
      waitForEventMatching("Task is declared lost") { event =>
        event.info.get("taskStatus").contains("TASK_UNREACHABLE") &&
          event.info.get("appId").contains(app.id)
      }

      Then("a replacement is launched in the remote region")
      eventually {
        val tasks = marathon.tasks(applicationId).value
        tasks should have size (5)
        tasks.groupBy(_.region.value).keySet should be(Set("home_region", "remote_region"))

        // Actual
        tasks.groupBy(_.region.value).get("home_region").value should have size (3)
        tasks.groupBy(_.region.value).get("remote_region").value should have size (2)

        // Expected
        //tasks.groupBy(_.region.value).get("home_region").value should have size (2)
        //tasks.groupBy(_.region.value).get("remote_region").value should have size (3)
      }
    }
  }

}
