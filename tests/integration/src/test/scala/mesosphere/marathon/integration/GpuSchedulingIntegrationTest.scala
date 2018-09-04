package mesosphere.marathon
package integration

import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.MarathonFacade._
import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.raml.{App, AppUpdate}
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.PathId

import scala.collection.immutable.Seq

class GpuSchedulingIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override def agentsGpus = Some(4)

  override val marathonArgs: Map[String, String] = Map(
    "enable_features" -> "gpu_resources",
    "gpu_scheduling_behavior" -> "restricted"
  )

  def createAsynchronously(app: App): RestResult[App] = {
    val result = marathon.createAppV2(app)
    result should be(Created)
    extractDeploymentIds(result) should have size 1
    result
  }

  def scaleToSuccessfully(appId: PathId, instances: Int): Seq[ITEnrichedTask] = {
    val result = marathon.updateApp(appId, AppUpdate(instances = Some(instances)))
    result should be(OK)
    waitForDeployment(result)
    waitForTasks(appId, instances)
  }

  def suspendSuccessfully(appId: PathId): Seq[ITEnrichedTask] = scaleToSuccessfully(appId, 0)

  def appId(suffix: Option[String] = None): PathId = testBasePath / s"app-${suffix.getOrElse(UUID.randomUUID)}"

  "Marathon with a restrictive GPU policy on agents with GPU" should {
    "deploy an app with GPU requirements" in {
      Given("a new app")
      val app = appProxy(appId(Some("with-gpu-resources")), "v1", instances = 1, healthCheck = None, gpus = 1)

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1) //make sure, the app has really started
    }

    "not match any offers for an app without GPU requirements" in {
      Given("a new app")
      val applicationId = appId(Some("without-gpu-resources"))
      val app = appProxy(applicationId, "v1", instances = 1, healthCheck = None, gpus = 0)

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1

      And("DeclinedScarceResources reject must happen")
      waitForAppOfferReject(applicationId, "DeclinedScarceResources")
    }

    "match an offer for an app without GPU requirements if it has an override label" in {
      Given("a new app")
      val app = appProxy(appId(Some("no-gpu-but-override")), "v1", instances = 1, healthCheck = None, gpus = 0)
        .copy(labels = Map("GPU_SCHEDULING_BEHAVIOR" -> "unrestricted"))

      When("The app is deployed")
      val result = marathon.createAppV2(app)

      Then("The app is created")
      result should be(Created)
      extractDeploymentIds(result) should have size 1
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1)
    }

    "match an offer for already existing persistent volume" in {
      Given("An app that writes into a persistent volume")
      val containerPath = "persistent-volume"
      val app = residentApp(
        id = appId(Some("resident-task-with-persistent-volume-on-gpu")),
        containerPath = containerPath,
        cmd = s"""echo data > $containerPath/data && sleep 1000""")
        .copy(labels = Map("GPU_SCHEDULING_BEHAVIOR" -> "unrestricted"))

      When("a task is launched")
      val result = createAsynchronously(app)

      Then("it successfully writes to the persistent volume and then finishes")
      waitForStatusUpdates("TASK_RUNNING")
      waitForDeployment(result)

      When("the app is suspended")
      suspendSuccessfully(PathId(app.id))

      And("a new task is started that checks for the previously written file")
      // deploy a new version that checks for the data written the above step
      val update = marathon.updateApp(
        PathId(app.id),
        AppUpdate(
          instances = Some(1),
          cmd = Some(s"""test -e $containerPath/data && sleep 2""")
        )
      )
      update.code shouldBe 200
      // we do not wait for the deployment to finish here to get the task events

      Then("there should be a match regardless selected policy")
      waitForStatusUpdates("TASK_RUNNING")
      waitForDeployment(update)
      waitForStatusUpdates("TASK_FINISHED")
    }

    "reject offers for new apps with a persistent volume and no GPUs" in {
      Given("An app that writes into a persistent volume")
      val containerPath = "persistent-volume"
      val applicationId = appId(Some("new-resident-task-no-match"))
      val app = residentApp(
        id = applicationId,
        containerPath = containerPath,
        cmd = s"""echo "data" > $containerPath/data""")

      When("A task is launched")
      val result = createAsynchronously(app)
      result should be(Created)

      Then("There is no match")
      waitForAppOfferReject(applicationId, "DeclinedScarceResources")

    }

  }

}
