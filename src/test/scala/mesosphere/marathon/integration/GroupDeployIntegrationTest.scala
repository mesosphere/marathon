package mesosphere.marathon.integration

import org.scalatest._
import mesosphere.marathon.api.v2.{ScalingStrategy, Group}
import mesosphere.marathon.api.v1.AppDefinition
import scala.concurrent.duration._
import mesosphere.marathon.integration.setup.{SingleMarathonIntegrationTest, IntegrationFunSuite}

class GroupDeployIntegrationTest
  extends IntegrationFunSuite
  with SingleMarathonIntegrationTest
  with Matchers
  with BeforeAndAfter
  with GivenWhenThen {

  //clean up state before running the test case
  before(marathon.cleanUp())

  test("create empty group successfully") {
    Given("A group which does not exist in marathon")
    val group = Group.empty().copy(id="test")

    When("The group gets created")
    val result = marathon.createGroup(group)

    Then("The group is created. A success event for this group is send.")
    result.code should be(204) //no content
    val event = waitForEvent("group_change_success")
    event.info("groupId") should be(group.id)
  }

  test("update empty group successfully") {
    Given("An existing group")
    val group = Group.empty().copy(id="test2")
    marathon.createGroup(group)
    waitForEvent("group_change_success")

    When("The group gets updated")
    marathon.updateGroup(group.copy(scalingStrategy=ScalingStrategy(1)))
    waitForEvent("group_change_success")

    Then("The group is updated")
    val result = marathon.group("test2")
    result.code should be(200)
    result.value.scalingStrategy should be(ScalingStrategy(1))
  }

  test("deleting an existing group gives a 200 http response") {
    Given("An existing group")
    val group = Group.empty().copy(id="test2")
    marathon.createGroup(group)
    waitForEvent("group_change_success")

    When("The group gets deleted")
    val result = marathon.deleteGroup(group.id)

    Then("The group is deleted")
    result.code should be(200)
    marathon.listGroups.value should be('empty)
  }

  test("delete a non existing group should give a 204 http response") {
    When("A non existing group is deleted")
    val missing = marathon.deleteGroup("does_not_exist")

    Then("We get a 204 http resonse code")
    missing.code should be(204)
  }

  test("create a group with applications to start") {
    Given("A group with one application")
    val app = AppDefinition(id="sleep", executor="//cmd", cmd="sleep 100", instances=2, cpus=0.1, mem=16)
    val group = Group("sleep", ScalingStrategy(0), Seq(app))

    When("The group is created")
    marathon.createGroup(group)
    waitForEvent("group_change_success")

    Then("A success event is send and the application has been started")
    val tasks = waitForTasks(app.id, app.instances)
    tasks should have size 2
  }

  //TODO(MV): this test is valid from my point of view, but the event after upgrade is not fired
  ignore("update a group with applications to restart") {
    Given("A group with one application started")
    val app1V1 = AppDefinition(id="app1", executor="//cmd", cmd="tail -f /dev/null", instances=2, cpus=0.1, mem=16)
    marathon.createGroup(Group("sleep", ScalingStrategy(0), Seq(app1V1)))
    waitForEvent("group_change_success")
    waitForTasks(app1V1.id, app1V1.instances)

    When("The group is updated, with a changed application")
    val app1V2 = AppDefinition(id="app1", executor="//cmd", cmd="tail -F /dev/null", instances=1, cpus=0.1, mem=16)
    marathon.updateGroup(Group("sleep", ScalingStrategy(0), Seq(app1V2)))
    waitForEvent("group_change_success", 120.seconds)

    Then("A success event is send and the application has been started")
    waitForTasks(app1V2.id, app1V2.instances)
  }

  ignore("create a group with application with health checks") {
    Given("A group with one application")
    val check = healthCheck(0, state = false)
    check.afterDelay(3.seconds, state = true)
    val appDef = AppDefinition(id="sleep", executor="//cmd", cmd="sleep 100", instances=3, cpus=0.1, mem=16,
      ports=Seq(config.httpPort), healthChecks=Set(check.toHealthCheck))
    val group = Group("sleep", ScalingStrategy(1), Seq(appDef))

    When("The group is created")
    marathon.createGroup(group)
    waitForEvent("group_change_success")

    Then("A success event is send and the application has been started")
    marathon.listApps.value.find(_.id=="sleep") should be('defined)
  }



}
