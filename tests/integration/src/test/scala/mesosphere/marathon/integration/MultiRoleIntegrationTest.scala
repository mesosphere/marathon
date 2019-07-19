package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.{BaseMarathon, EmbeddedMarathonTest, MesosConfig}
import mesosphere.marathon.raml.AppUpdate
import mesosphere.marathon.state.PathId
import org.scalatest.time.{Seconds, Span}

class MultiRoleIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override implicit lazy val patienceConfig = PatienceConfig(timeout = Span(300, Seconds), interval = Span(1, Seconds))

  override lazy val mesosConfig = MesosConfig(
    restrictedToRoles = None
  )

  "MultiRole" should {

    "Marathon should launch tasks as specified role" in {
      Given("an app in role dev")
      val appInDev = appProxy(PathId("/dev/app-with-role"), "v1", instances = 1, role = Some("dev"))

      When("The app is deployed")
      val resultInDev = marathon.createAppV2(appInDev)

      Then("The apps are created")
      resultInDev should be(Created)
      waitForDeployment(resultInDev)
      waitForTasks(PathId(appInDev.id), 1) //make sure the app has really started

      Given("an app in role metrics")
      val appInMetrics = appProxy(PathId("/metrics/app-with-role"), "v1", instances = 1, role = Some("metrics"))

      When("The app is deployed")
      val resultInMetrics = marathon.createAppV2(appInMetrics)

      Then("The apps are created")
      resultInMetrics should be(Created)
      waitForDeployment(resultInMetrics)
      waitForTasks(PathId(appInMetrics.id), 1) //make sure the app has really started
    }

    "Marathon should launch an resident app as specified role" in {
      Given("an app in role dev")
      val appInDev = residentApp(PathId("/dev/simple-resident-app-with-role"), role = Some("foo"))

      When("The app is deployed")
      val resultInDev = marathon.createAppV2(appInDev)

      Then("The apps are created")
      resultInDev should be(Created)
      waitForDeployment(resultInDev)
      waitForTasks(PathId(appInDev.id), 1) //make sure the app has really started
    }

    "An Instance should keep it's previous role in a resident app" in {
      val appId = PathId("/dev/resident-app-with-role")

      Given("an app in with the default role")
      val appInDev = residentApp(appId, instances = 1, role = Some(BaseMarathon.defaultRole), cmd = "sleep 10000")

      When("The app is deployed")
      val resultInDev = marathon.createAppV2(appInDev)

      Then("The apps are created")
      resultInDev should be(Created)
      waitForDeployment(resultInDev)
      waitForTasks(PathId(appInDev.id), 1) //make sure the app has really started

      Given("The App is updated to a new role")
      val updatedApp = AppUpdate(id = Some(appId.toString), role = Some("dev"), acceptedResourceRoles = Some(Set("*")))

      When("The Update is deployed")
      val resultOfUpdate = marathon.updateApp(appId, updatedApp)

      Then("The update is ready")
      resultOfUpdate should be(OK)
      waitForDeployment(resultOfUpdate)

      waitForTasks(PathId(appInDev.id), 1) //make sure the app has restarted

      Given("The Update is done")
      var taskListResult = marathon.tasks(appId)

      Then("One Instance should have the old role, and one the new role")
      var taskList = taskListResult.value

      taskList.size should be(1)
      taskList.head.role should be(BaseMarathon.defaultRole)

      Given("The app is scaled to 2")
      val resultOfScale = marathon.updateApp(appId, updatedApp.copy(instances = Some(2)))

      resultOfUpdate should be(OK)
      waitForDeployment(resultOfUpdate)

      waitForTasks(PathId(appInDev.id), 1) //make sure the app has restarted

      Given("The Update is done")
      taskListResult = marathon.tasks(appId)

      Then("One Instance should have the old role, and one the new role")
      taskList = taskListResult.value

      taskList.size should be(2)
      val instanceRoles = taskList.map(task => task.role)
      instanceRoles should contain("dev")
      instanceRoles should contain(BaseMarathon.defaultRole)

    }

  }
}
