package mesosphere.marathon
package integration

import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.facades.MarathonFacade.extractDeploymentIds
import mesosphere.marathon.integration.facades.MesosFacade.ITResourceStringValue
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.raml.App
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId._

import scala.concurrent.duration._

@IntegrationTest
class RemoteRegionOffersIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override lazy val mesosNumMasters = 1
  override lazy val mesosNumSlaves = 2

  val homeRegionName = "home"
  override def mastersFaultDomains = Seq(Some(FaultDomain(region = homeRegionName, zone = "zone1")))

  override def agentsFaultDomains = Seq(Some(FaultDomain(region = "remote1", zone = "zone1")), Some(FaultDomain(region = homeRegionName, zone = "zone1")))

  before(cleanUp())

  def appId(suffix: Option[String] = None): PathId = testBasePath / s"app-${suffix.getOrElse(UUID.randomUUID)}"

  "Region Aware marathon" must {
    "Launch an instance of the app in the default region if region is not specified" in {
      val applicationId = appId(Some("must-be-placed-in-home"))
      val app = appProxy(applicationId, "v1", instances = 1, healthCheck = None)

      When("The app is deployed without specifying region")
      logger.info("creating app")
      val result = marathon.createAppV2(app)

      Then("The app is created in the default region")
      result should be(Created)
      logger.info("app created")
      extractDeploymentIds(result)
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1)
      logger.info("tasks started")
      val slaveId = marathon.tasks(applicationId).value.head.slaveId.get
      val foo = mesos.state.value.agents.find(_.id == slaveId).get.attributes.attributes("fault_domain_region")

      foo match {
        case ITResourceStringValue(value) => value shouldEqual "remote1"
      }

    }
    "Launch an instance of the app in the specified region" in {}
    "Launch an instance of the app in the specified region and zone" in {}
    "Respond with a validation error if zone is specified without region" in {}
    "Apply UNIQUE constraint for remote regions" in {}
  }

}
