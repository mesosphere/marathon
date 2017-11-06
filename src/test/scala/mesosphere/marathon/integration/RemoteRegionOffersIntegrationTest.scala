package mesosphere.marathon
package integration

import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.facades.MarathonFacade.extractDeploymentIds
import mesosphere.marathon.integration.facades.MesosFacade.ITResourceStringValue
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.raml.App
import mesosphere.marathon.state.{ FaultDomain, PathId, Region, Zone }
import mesosphere.marathon.state.PathId._
import mesosphere.mesos.Constraints
import org.scalatest.Inside

import scala.concurrent.duration._

@IntegrationTest
class RemoteRegionOffersIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Inside {

  override lazy val mesosNumMasters = 1
  override lazy val mesosNumSlaves = 3

  class Fixture {
    val homeRegion = Region("home_region")
    val homeZone = Zone("home_zone")

    val remoteRegion = Region("remote_region")
    val remoteZone1 = Zone("remote_zone1")
    val remoteZone2 = Zone("remote_zone2")
  }

  val f = new Fixture

  override def mastersFaultDomains = Seq(Some(FaultDomain(region = f.homeRegion, zone = f.homeZone)))

  override def agentsFaultDomains = Seq(
    Some(FaultDomain(region = f.remoteRegion, zone = f.remoteZone1)),
    Some(FaultDomain(region = f.remoteRegion, zone = f.remoteZone2)),
    Some(FaultDomain(region = f.homeRegion, zone = f.homeZone)))

  def appId(suffix: String): PathId = testBasePath / s"app-${suffix}"

  "Region Aware marathon" must {
    "Launch an instance of the app in the default region if region is not specified" in {
      val applicationId = appId("must-be-placed-in-home-region")
      val app = appProxy(applicationId, "v1", instances = 1, healthCheck = None)

      When("The app is deployed without specifying region")
      val result = marathon.createAppV2(app)

      Then("The app is created in the default region")
      result should be(Created)

      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1)
      val slaveId = marathon.tasks(applicationId).value.head.slaveId.get
      val agentRegion = mesos.state.value.agents.find(_.id == slaveId).get.attributes.attributes("fault_domain_region")

      inside(agentRegion) {
        case ITResourceStringValue(value) => value shouldEqual f.homeRegion.value
      }
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
      waitForTasks(app.id.toPath, 1)
      val slaveId = marathon.tasks(applicationId).value.head.slaveId.get
      val agentRegion = mesos.state.value.agents.find(_.id == slaveId).get.attributes.attributes("fault_domain_region")

      inside(agentRegion) {
        case ITResourceStringValue(value) => value shouldEqual f.remoteRegion.value
      }
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
      waitForTasks(app.id.toPath, 1)
      val slaveId = marathon.tasks(applicationId).value.head.slaveId.get
      val agentRegion = mesos.state.value.agents.find(_.id == slaveId).get.attributes.attributes("fault_domain_region")
      val agentZone = mesos.state.value.agents.find(_.id == slaveId).get.attributes.attributes("fault_domain_zone")

      inside(agentRegion) {
        case ITResourceStringValue(value) => value shouldEqual f.remoteRegion.value
      }
      inside(agentZone) {
        case ITResourceStringValue(value) => value shouldEqual f.remoteZone2.value
      }
    }
  }

}
