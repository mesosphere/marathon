package mesosphere.marathon.integration

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.GroupUpdate
import mesosphere.marathon.integration.setup.{ IntegrationFunSuite, SingleMarathonIntegrationTest }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.UpgradeStrategy
import org.scalatest._

import scala.concurrent.duration._

class GroupDeployIntegrationTest
    extends IntegrationFunSuite
    with SingleMarathonIntegrationTest
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen {

  //clean up state before running the test case
  before(cleanUp())

  test("create empty group successfully") {
    Given("A group which does not exist in marathon")
    val group = GroupUpdate.empty("test".toRootPath)

    When("The group gets created")
    val result = marathon.createGroup(group)

    Then("The group is created. A success event for this group is send.")
    result.code should be(201) //created
    val event = waitForEvent("group_change_success")
    event.info("groupId") should be(group.id.get.toString)
  }

  test("update empty group successfully") {
    Given("An existing group")
    val name = "test2".toRootPath
    val group = GroupUpdate.empty(name)
    val dependencies = Set("/test".toPath)
    marathon.createGroup(group)
    waitForEvent("group_change_success")

    When("The group gets updated")
    marathon.updateGroup(name, group.copy(dependencies = Some(dependencies)))
    waitForEvent("group_change_success")

    Then("The group is updated")
    val result = marathon.group("test2".toRootPath)
    result.code should be(200)
    result.value.dependencies should be(dependencies)
  }

  test("deleting an existing group gives a 200 http response") {
    Given("An existing group")
    val group = GroupUpdate.empty("test3".toRootPath)
    marathon.createGroup(group)
    waitForEvent("group_change_success")

    When("The group gets deleted")
    val result = marathon.deleteGroup(group.id.get)
    waitForEvent("group_change_success")

    Then("The group is deleted")
    result.code should be(200)
    marathon.listGroups.value should be('empty)
  }

  test("delete a non existing group should give a 404 http response") {
    When("A non existing group is deleted")
    val missing = marathon.deleteGroup("does_not_exist".toRootPath)

    Then("We get a 204 http resonse code")
    missing.code should be(404)
  }

  test("create a group with applications to start") {
    Given("A group with one application")
    val app = appProxy("/test/app".toRootPath, "v1", 2, withHealth = false)
    val group = GroupUpdate("/test".toRootPath, Set(app))

    When("The group is created")
    marathon.createGroup(group)
    waitForEvent("group_change_success")

    Then("A success event is send and the application has been started")
    val tasks = waitForTasks(app.id, app.instances)
    tasks should have size 2
  }

  test("update a group with applications to restart") {
    Given("A group with one application started")
    val id = "test".toRootPath
    val appId = id / "app"
    val app1V1 = appProxy(appId, "v1", 2, withHealth = false)
    marathon.createGroup(GroupUpdate(id, Set(app1V1)))
    waitForEvent("group_change_success")
    waitForTasks(app1V1.id, app1V1.instances)

    When("The group is updated, with a changed application")
    val app1V2 = appProxy(appId, "v2", 2, withHealth = false)
    marathon.updateGroup(id, GroupUpdate(id, Set(app1V2)))
    waitForEvent("group_change_success")

    Then("A success event is send and the application has been started")
    waitForTasks(app1V2.id, app1V2.instances)
  }

  test("create a group with application with health checks") {
    Given("A group with one application")
    val id = "proxy".toRootPath
    val appId = id / "app"
    val proxy = appProxy(appId, "v1", 1)
    val group = GroupUpdate(id, Set(proxy))

    When("The group is created")
    marathon.createGroup(group)

    Then("A success event is send and the application has been started")
    waitForEvent("group_change_success")
  }

  test("upgrade a group with application with health checks") {
    Given("A group with one application")
    val id = "test".toRootPath
    val appId = id / "app"
    val proxy = appProxy(appId, "v1", 1)
    val group = GroupUpdate(id, Set(proxy))
    marathon.createGroup(group)
    waitForEvent("group_change_success")
    val check = appProxyCheck(proxy.id, "v1", state = true)

    When("The group is updated")
    check.afterDelay(1.second, state = false)
    check.afterDelay(3.seconds, state = true)
    marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 1)))))

    Then("A success event is send and the application has been started")
    waitForEvent("group_change_success")
  }

  test("rollback from an upgrade of group") {
    Given("A group with one application")
    val gid = "proxy".toRootPath
    val appId = gid / "app"
    val proxy = appProxy(appId, "v1", 2)
    val group = GroupUpdate(gid, Set(proxy))
    val create = marathon.createGroup(group)
    waitForEvent("group_change_success")
    waitForTasks(proxy.id, proxy.instances)
    val v1Checks = appProxyCheck(appId, "v1", state = true)

    When("The group is updated")
    marathon.updateGroup(gid, group.copy(apps = Some(Set(appProxy(appId, "v2", 2)))))
    waitForEvent("group_change_success")

    Then("The new version is deployed")
    val v2Checks = appProxyCheck(appId, "v2", state = true)
    validFor("all v2 apps are available", 10.seconds) { v2Checks.pingSince(2.seconds) }

    When("A rollback to the first version is initiated")
    marathon.rollbackGroup(gid, create.value.version, force = true)
    waitForEvent("group_change_success")

    Then("The rollback will be performed and the old version is available")
    validFor("all v1 apps are available", 10.seconds) { v1Checks.pingSince(2.seconds) }
  }

  test("during Deployment the defined minimum health capacity is never undershot") {
    Given("A group with one application")
    val id = "test".toRootPath
    val appId = id / "app"
    val proxy = appProxy(appId, "v1", 2).copy(upgradeStrategy = UpgradeStrategy(1, None))
    val group = GroupUpdate(id, Set(proxy))
    marathon.createGroup(group)
    waitForEvent("group_change_success")
    waitForTasks(appId, proxy.instances)
    val v1Check = appProxyCheck(appId, "v1", state = true)

    When("The new application is not healthy")
    val v2Check = appProxyCheck(appId, "v2", state = false) //will always fail
    marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 2)))))

    Then("All v1 applications are kept alive")
    validFor("all v1 apps are always available", 15.seconds) { v1Check.pingSince(3.seconds) }

    When("The new application becomes healthy")
    v2Check.state = true //make v2 healthy, so the app can be cleaned
    waitForEvent("group_change_success")
  }

  test("An upgrade in progress can not be interrupted without force") {
    Given("A group with one application with an upgrade in progress")
    val id = "forcetest".toRootPath
    val appId = id / "app"
    val proxy = appProxy(appId, "v1", 2)
    val group = GroupUpdate(id, Set(proxy))
    marathon.createGroup(group)
    waitForEvent("group_change_success")
    appProxyCheck(appId, "v2", state = false) //will always fail
    marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 2)))))

    When("Another upgrade is triggered, while the old one is not completed")
    marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v3", 2)))))

    Then("An error is indicated")
    waitForEvent("group_change_failed")

    When("Another upgrade is triggered with force, while the old one is not completed")
    marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v4", 2)))), force = true)

    Then("The update is performed")
    waitForEvent("group_change_success")
  }
}
