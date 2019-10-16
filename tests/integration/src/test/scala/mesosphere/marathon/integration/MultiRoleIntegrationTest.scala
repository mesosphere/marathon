package mesosphere.marathon
package integration

import com.mesosphere.utils.mesos.MesosConfig
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.{BaseMarathon, EmbeddedMarathonTest}
import mesosphere.marathon.raml.AppUpdate
import mesosphere.marathon.state.AbsolutePathId
import org.scalatest.time.{Seconds, Span}

class MultiRoleIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override implicit lazy val patienceConfig = PatienceConfig(timeout = Span(300, Seconds), interval = Span(1, Seconds))

  override lazy val mesosConfig = MesosConfig(
    restrictedToRoles = None
  )
  override lazy val marathonArgs = Map(
    "mesos_role" -> "foo"
  )

  "MultiRole" should {

    "Marathon should launch tasks as specified role" in {
      Given("an app in role dev")
      val appInDev = appProxy(AbsolutePathId("/dev/app-with-role"), "v1", instances = 1, role = Some("dev"))

      When("The app is deployed")
      val resultInDev = marathon.createAppV2(appInDev)

      Then("The apps are created")
      resultInDev should be(Created)
      waitForDeployment(resultInDev)
      waitForTasks(AbsolutePathId(appInDev.id), 1) //make sure the app has really started

      Given("an app in role metrics")
      val appInMetrics = appProxy(AbsolutePathId("/metrics/app-with-role"), "v1", instances = 1, role = Some("metrics"))

      When("The app is deployed")
      val resultInMetrics = marathon.createAppV2(appInMetrics)

      Then("The apps are created")
      resultInMetrics should be(Created)
      waitForDeployment(resultInMetrics)
      waitForTasks(AbsolutePathId(appInMetrics.id), 1) //make sure the app has really started
    }

    "Marathon should launch an resident app as specified role" in {
      Given("an app in role dev")
      val appInDev = residentApp(AbsolutePathId("/dev/simple-resident-app-with-role"), role = Some("foo"))

      When("The app is deployed")
      val resultInDev = marathon.createAppV2(appInDev)

      Then("The apps are created")
      resultInDev should be(Created)
      waitForDeployment(resultInDev)
      waitForTasks(AbsolutePathId(appInDev.id), 1) //make sure the app has really started
    }

    "An Instance should keep it's previous role in a resident app" in {
      val appId = AbsolutePathId("/dev/resident-app-keep-previous-role")

      Given("an app in with the default role")
      val appInDev = residentApp(appId, instances = 1, role = Some(BaseMarathon.defaultRole), cmd = "sleep 10000")

      When("The app is deployed")
      val resultInDev = marathon.createAppV2(appInDev)

      Then("The apps are created")
      resultInDev should be(Created)
      waitForDeployment(resultInDev)
      waitForTasks(AbsolutePathId(appInDev.id), 1) //make sure the app has really started

      Given("The App is updated to a new role")
      val updatedApp = AppUpdate(id = Some(appId.toString), role = Some("dev"), acceptedResourceRoles = Some(Set("*")))

      When("The Update is deployed")
      val resultOfUpdate = marathon.updateApp(appId, updatedApp, force = true)

      Then("The update is ready")
      resultOfUpdate should be(OK)
      waitForDeployment(resultOfUpdate)

      waitForTasks(AbsolutePathId(appInDev.id), 1) //make sure the app has restarted

      Given("The Update is done")
      var taskListResult = marathon.tasks(appId)

      Then("One Instance should have the old role, and one the new role")
      var taskList = taskListResult.value

      taskList should have size (1)
      taskList.head.role.value should be(BaseMarathon.defaultRole)

      Given("The app is scaled to 2")
      val resultOfScale = marathon.updateApp(appId, updatedApp.copy(instances = Some(2)))

      resultOfScale should be(OK)
      waitForDeployment(resultOfScale)

      waitForTasks(AbsolutePathId(appInDev.id), 2) // make sure both apps have started

      Given("The Update is done")
      taskListResult = marathon.tasks(appId)

      Then("One Instance should have the old role, and one the new role")
      taskList = taskListResult.value

      taskList should have size (2)
      val instanceRoles = taskList.map(task => task.role.value)
      instanceRoles should contain("dev")
      instanceRoles should contain(BaseMarathon.defaultRole)

    }

    "Marathon should launch an resident app as non-default role" in {
      Given("an app in role dev")
      val appInDev = residentApp(AbsolutePathId("/dev/resident-app-with-non-default-role"), role = Some("dev"))

      When("The app is deployed")
      val resultInDev = marathon.createAppV2(appInDev)

      Then("The app is created")
      resultInDev should be(Created)
      waitForDeployment(resultInDev)
      waitForTasks(AbsolutePathId(appInDev.id), 1) //make sure the app has really started
    }

  }

}
