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
    marathon.updateGroup(group.copy(scalingStrategy=ScalingStrategy(1, None)))
    waitForEvent("group_change_success")

    Then("The group is updated")
    val result = marathon.group("test2")
    result.code should be(200)
    result.value.scalingStrategy should be(ScalingStrategy(1, None))
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
    val group = Group("sleep", ScalingStrategy(0, None), Seq(app))

    When("The group is created")
    marathon.createGroup(group)
    waitForEvent("group_change_success")

    Then("A success event is send and the application has been started")
    val tasks = waitForTasks(app.id, app.instances)
    tasks should have size 2
  }

  test("update a group with applications to restart") {
    Given("A group with one application started")
    val app1V1 = AppDefinition(id="app1", executor="//cmd", cmd="tail -f /dev/null", instances=2, cpus=0.1, mem=16)
    marathon.createGroup(Group("sleep", ScalingStrategy(0, None), Seq(app1V1)))
    waitForEvent("group_change_success")
    waitForTasks(app1V1.id, app1V1.instances)

    When("The group is updated, with a changed application")
    val app1V2 = AppDefinition(id="app1", executor="//cmd", cmd="tail -F /dev/null", instances=1, cpus=0.1, mem=16)
    marathon.updateGroup(Group("sleep", ScalingStrategy(0, None), Seq(app1V2)))
    waitForEvent("group_change_success", 60.seconds)

    Then("A success event is send and the application has been started")
    waitForTasks(app1V2.id, app1V2.instances, 60.seconds)
  }

  test("create a group with application with health checks") {
    Given("A group with one application")
    val name = "proxy"
    val proxy = appProxy(name, "v1", 1)
    val group = Group(name, ScalingStrategy(1, None), Seq(proxy))

    When("The group is created")
    marathon.createGroup(group)

    Then("A success event is send and the application has been started")
    waitForEvent("group_change_success")
  }

  test("upgrade a group with application with health checks") {
    Given("A group with one application")
    val name = "proxy"
    val proxy = appProxy(name, "v1", 1)
    val group = Group(name, ScalingStrategy(1, None), Seq(proxy))
    marathon.createGroup(group)
    waitForEvent("group_change_success")
    val check = appProxyChecks("proxy", "v1", state=true).head

    When("The group is updated")
    check.afterDelay(1.second, state = false)
    check.afterDelay(3.seconds, state = true)
    marathon.updateGroup(group.copy(apps=Seq(proxy.copy(cmd=proxy.cmd + " version2"))))

    Then("A success event is send and the application has been started")
    waitForEvent("group_change_success")
  }

  test("rollback from an upgrade of group") {
    Given("A group with one application")
    val name = "proxy"
    val proxy = appProxy(name, "v1", 2)
    val group = Group(name, ScalingStrategy(1, None), Seq(proxy))
    marathon.createGroup(group)
    waitForEvent("group_change_success")
    val v1Checks = appProxyChecks("proxy", "v1", state=true)
    val v2Checks = appProxyChecks("proxy", "v2", state=true)

    When("The group is updated")
    marathon.updateGroup(group.copy(apps=Seq(appProxy(name, "v2", 2))))

    Then("The new version is deployed")
    waitForEvent("group_change_success")
    validFor("all v2 apps are available", 10.seconds) { v2Checks.forall(_.pingSince(2.seconds)) }

    When("A rollback to the first version is initiated")
    marathon.rollbackGroup(name, group.version.toString, force=true)

    Then("The rollback will be performed and the old version is available")
    waitForEvent("group_change_success")
    validFor("all v1 apps are available", 10.seconds) { v1Checks.forall(_.pingSince(2.seconds)) }
  }

  test("during Deployment the defined minimum health capacity is never undershot") {
    Given("A group with one application")
    val name = "proxy"
    val proxy = appProxy(name, "v1", 2)
    val group = Group(name, ScalingStrategy(1, None), Seq(proxy))
    marathon.createGroup(group)
    waitForEvent("group_change_success")

    When("The new application is not healthy")
    val v1Checks = appProxyChecks("proxy", "v1", state=true)
    val v2Checks = appProxyChecks("proxy", "v2", state=false) //will always fail
    marathon.updateGroup(group.copy(apps=Seq(appProxy(name, "v2", 2))))

    Then("All v1 applications are kept alive")
    waitUntil("health check for v1 is initiated", proxy.healthChecks.head.gracePeriod) {
      v1Checks.forall(_.pingSince(2.seconds))
    }
    validFor("all v1 apps are always available", 15.seconds) {
      v1Checks.forall(_.pingSince(2.seconds))
    }

    When("The new application becomes healthy")
    v2Checks.foreach(_.state=true) //make v2 healthy, so the app can be cleaned
    waitForEvent("group_change_success")
  }


  test("An upgrade in progress can not be interrupted without force") {
    Given("A group with one application with an upgrade in progress")
    val name = "proxy"
    val proxy = appProxy(name, "v1", 2)
    val group = Group(name, ScalingStrategy(1, None), Seq(proxy))
    marathon.createGroup(group)
    waitForEvent("group_change_success")
    appProxyChecks("proxy", "v2", state=false) //will always fail
    marathon.updateGroup(group.copy(apps=Seq(appProxy(name, "v2", 2))))

    When("Another upgrade is triggered, while the old one is not completed")
    marathon.updateGroup(group.copy(apps=Seq(appProxy(name, "v3", 2))))

    Then("An error is indicated")
    waitForEvent("group_change_failed")

    When("Another upgrade is triggered with force, while the old one is not completed")
    marathon.updateGroup(group.copy(apps=Seq(appProxy(name, "v4", 2))), force = true)

    Then("The update is performed")
    waitForEvent("group_change_success")
  }
}
