package mesosphere.marathon.integration

import mesosphere.marathon.api.v2.GroupUpdate
import mesosphere.marathon.integration.setup.{ IntegrationHealthCheck, IntegrationFunSuite, SingleMarathonIntegrationTest }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, PathId, UpgradeStrategy }
import org.scalatest._
import spray.http.DateTime
import spray.httpx.UnsuccessfulResponseException

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
    val event = waitForChange(result)
  }

  test("update empty group successfully") {
    Given("An existing group")
    val name = "test2".toRootPath
    val group = GroupUpdate.empty(name)
    val dependencies = Set("/test".toPath)
    waitForChange(marathon.createGroup(group))

    When("The group gets updated")
    waitForChange(marathon.updateGroup(name, group.copy(dependencies = Some(dependencies))))

    Then("The group is updated")
    val result = marathon.group("test2".toRootPath)
    result.code should be(200)
    result.value.dependencies should be(dependencies)
  }

  test("deleting an existing group gives a 200 http response") {
    Given("An existing group")
    val group = GroupUpdate.empty("test3".toRootPath)
    waitForChange(marathon.createGroup(group))

    When("The group gets deleted")
    val result = marathon.deleteGroup(group.id.get)
    waitForChange(result)

    Then("The group is deleted")
    result.code should be(200)
    marathon.listGroups.value should be('empty)
  }

  test("delete a non existing group should give a 404 http response") {
    When("A non existing group is deleted")
    val result = intercept[UnsuccessfulResponseException] {
      val missing = marathon.deleteGroup("does_not_exist".toRootPath)
    }

    Then("We get a 404 http response code")
    result.response.status.intValue should be(404)
  }

  test("create a group with applications to start") {
    Given("A group with one application")
    val app = appProxy("/test/app".toRootPath, "v1", 2, withHealth = false)
    val group = GroupUpdate("/test".toRootPath, Set(app))

    When("The group is created")
    waitForChange(marathon.createGroup(group))

    Then("A success event is send and the application has been started")
    val tasks = waitForTasks(app.id, app.instances)
    tasks should have size 2
  }

  test("update a group with applications to restart") {
    Given("A group with one application started")
    val id = "test".toRootPath
    val appId = id / "app"
    val app1V1 = appProxy(appId, "v1", 2, withHealth = false)
    waitForChange(marathon.createGroup(GroupUpdate(id, Set(app1V1))))
    waitForTasks(app1V1.id, app1V1.instances)

    When("The group is updated, with a changed application")
    val app1V2 = appProxy(appId, "v2", 2, withHealth = false)
    waitForChange(marathon.updateGroup(id, GroupUpdate(id, Set(app1V2))))

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
    val create = marathon.createGroup(group)

    Then("A success event is send and the application has been started")
    waitForChange(create)
  }

  test("upgrade a group with application with health checks") {
    Given("A group with one application")
    val id = "test".toRootPath
    val appId = id / "app"
    val proxy = appProxy(appId, "v1", 1)
    val group = GroupUpdate(id, Set(proxy))
    waitForChange(marathon.createGroup(group))
    val check = appProxyCheck(proxy.id, "v1", state = true)

    When("The group is updated")
    check.afterDelay(1.second, state = false)
    check.afterDelay(3.seconds, state = true)
    val update = marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 1)))))

    Then("A success event is send and the application has been started")
    waitForChange(update)
  }

  test("rollback from an upgrade of group") {
    Given("A group with one application")
    val gid = "proxy".toRootPath
    val appId = gid / "app"
    val proxy = appProxy(appId, "v1", 2)
    val group = GroupUpdate(gid, Set(proxy))
    val create = marathon.createGroup(group)
    waitForChange(create)
    waitForTasks(proxy.id, proxy.instances)
    val v1Checks = appProxyCheck(appId, "v1", state = true)

    When("The group is updated")
    waitForChange(marathon.updateGroup(gid, group.copy(apps = Some(Set(appProxy(appId, "v2", 2))))))

    Then("The new version is deployed")
    val v2Checks = appProxyCheck(appId, "v2", state = true)
    validFor("all v2 apps are available", 10.seconds) { v2Checks.pingSince(2.seconds) }

    When("A rollback to the first version is initiated")
    waitForChange(marathon.rollbackGroup(gid, create.value.version), 120.seconds)

    Then("The rollback will be performed and the old version is available")
    v1Checks.healthy
    validFor("all v1 apps are available", 10.seconds) { v1Checks.pingSince(2.seconds) }
  }

  test("during Deployment the defined minimum health capacity is never undershot") {
    Given("A group with one application")
    val id = "test".toRootPath
    val appId = id / "app"
    val proxy = appProxy(appId, "v1", 2).copy(upgradeStrategy = UpgradeStrategy(1))
    val group = GroupUpdate(id, Set(proxy))
    val create = marathon.createGroup(group)
    waitForChange(create)
    waitForTasks(appId, proxy.instances)
    val v1Check = appProxyCheck(appId, "v1", state = true)

    When("The new application is not healthy")
    val v2Check = appProxyCheck(appId, "v2", state = false) //will always fail
    val update = marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 2)))))

    Then("All v1 applications are kept alive")
    v1Check.healthy
    validFor("all v1 apps are always available", 15.seconds) { v1Check.pingSince(3.seconds) }

    When("The new application becomes healthy")
    v2Check.state = true //make v2 healthy, so the app can be cleaned
    waitForChange(update)
  }

  test("An upgrade in progress can not be interrupted without force") {
    Given("A group with one application with an upgrade in progress")
    val id = "forcetest".toRootPath
    val appId = id / "app"
    val proxy = appProxy(appId, "v1", 2)
    val group = GroupUpdate(id, Set(proxy))
    val create = marathon.createGroup(group)
    waitForChange(create)
    appProxyCheck(appId, "v2", state = false) //will always fail
    marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 2)))))

    When("Another upgrade is triggered, while the old one is not completed")
    intercept[UnsuccessfulResponseException] {
      marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v3", 2)))))
    }

    Then("An error is indicated")
    waitForEvent("group_change_failed")

    When("Another upgrade is triggered with force, while the old one is not completed")
    val force = marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v4", 2)))), force = true)

    Then("The update is performed")
    waitForChange(force)
  }

  test("Groups with Applications with circular dependencies can not get deployed") {
    Given("A group with 3 circular dependent applications")
    val db = appProxy("/test/db".toPath, "v1", 1, dependencies = Set("/test/frontend1".toPath))
    val service = appProxy("/test/service".toPath, "v1", 1, dependencies = Set(db.id))
    val frontend = appProxy("/test/frontend1".toPath, "v1", 1, dependencies = Set(service.id))
    val group = GroupUpdate("test".toPath, Set(db, service, frontend))

    When("The group gets posted")
    val exception = intercept[UnsuccessfulResponseException] {
      marathon.createGroup(group)
    }

    Then("An unsuccessfull response has been posted, with an error indicating cyclic dependencies")
    exception.response.entity.asString should include("cyclic dependencies")
  }

  test("Applications with dependencies get deployed in the correct order") {
    Given("A group with 3 dependent applications")
    val db = appProxy("/test/db".toPath, "v1", 1)
    val service = appProxy("/test/service".toPath, "v1", 1, dependencies = Set(db.id))
    val frontend = appProxy("/test/frontend1".toPath, "v1", 1, dependencies = Set(service.id))
    val group = GroupUpdate("test".toPath, Set(db, service, frontend))

    When("The group gets deployed")
    var ping = Map.empty[PathId, DateTime]
    def storeFirst(health: IntegrationHealthCheck) {
      if (!ping.contains(health.appId)) ping += health.appId -> DateTime.now
    }
    val dbHealth = appProxyCheck(db.id, "v1", state = true).withHealthAction(storeFirst)
    val serviceHealth = appProxyCheck(service.id, "v1", state = true).withHealthAction(storeFirst)
    val frontendHealth = appProxyCheck(frontend.id, "v1", state = true).withHealthAction(storeFirst)
    waitForChange(marathon.createGroup(group))

    Then("The correct order is maintained")
    ping should have size 3
    ping(db.id) should be < ping(service.id)
    ping(service.id) should be < ping(frontend.id)
  }

  test("Groups with dependencies get deployed in the correct order") {
    Given("A group with 3 dependent applications")
    val db = appProxy("/test/db/db1".toPath, "v1", 1)
    val service = appProxy("/test/service/service1".toPath, "v1", 1)
    val frontend = appProxy("/test/frontend/frontend1".toPath, "v1", 1)
    val group = GroupUpdate("test".toPath, Set.empty[AppDefinition], Set(
      GroupUpdate("db".toPath, apps = Set(db)),
      GroupUpdate("service".toPath, apps = Set(service)).copy(dependencies = Some(Set("/test/db".toPath))),
      GroupUpdate("frontend".toPath, apps = Set(frontend)).copy(dependencies = Some(Set("/test/service".toPath)))
    ))

    When("The group gets deployed")
    var ping = Map.empty[PathId, DateTime]
    def storeFirst(health: IntegrationHealthCheck) {
      if (!ping.contains(health.appId)) ping += health.appId -> DateTime.now
    }
    val dbHealth = appProxyCheck(db.id, "v1", state = true).withHealthAction(storeFirst)
    val serviceHealth = appProxyCheck(service.id, "v1", state = true).withHealthAction(storeFirst)
    val frontendHealth = appProxyCheck(frontend.id, "v1", state = true).withHealthAction(storeFirst)
    waitForChange(marathon.createGroup(group))

    Then("The correct order is maintained")
    ping should have size 3
    ping(db.id) should be < ping(service.id)
    ping(service.id) should be < ping(frontend.id)
  }

  test("Groups with dependant Applications get upgraded in the correct order with maintained upgrade strategy") {
    var ping = Map.empty[String, DateTime]
    def key(health: IntegrationHealthCheck) = s"${health.appId}_${health.versionId}"
    def storeFirst(health: IntegrationHealthCheck) {
      if (!ping.contains(key(health))) ping += key(health) -> DateTime.now
    }
    def create(version: String) = {
      val db = appProxy("/test/db".toPath, version, 1)
      val service = appProxy("/test/service".toPath, version, 1, dependencies = Set(db.id))
      val frontend = appProxy("/test/frontend1".toPath, version, 1, dependencies = Set(service.id))
      (GroupUpdate("test".toPath, Set(db, service, frontend)),
        appProxyCheck(db.id, version, state = true).withHealthAction(storeFirst),
        appProxyCheck(service.id, version, state = true).withHealthAction(storeFirst),
        appProxyCheck(frontend.id, version, state = true).withHealthAction(storeFirst))
    }

    Given("A group with 3 dependent applications")
    val (groupV1, dbV1, serviceV1, frontendV1) = create("v1")
    waitForChange(marathon.createGroup(groupV1))

    When("The group gets updated, where frontend2 is not healthy")
    val (groupV2, dbV2, serviceV2, frontendV2) = create("v2")
    frontendV2.state = false
    val upgrade = marathon.updateGroup(PathId.empty, groupV2)
    //it is the last in the dependency chain
    waitForHealthCheck(frontendV2)

    Then("The correct order is maintained")
    ping should have size 6
    ping(key(dbV1)) should be < ping(key(serviceV1))
    ping(key(serviceV1)) should be < ping(key(frontendV1))
    ping(key(dbV2)) should be < ping(key(serviceV2))
    ping(key(serviceV2)) should be < ping(key(frontendV2))
    validFor("all v1 apps are available as well as db v2 and service v2", 15.seconds) {
      dbV1.pingSince(2.seconds) &&
        serviceV1.pingSince(2.seconds) &&
        frontendV1.pingSince(2.seconds) &&
        dbV2.pingSince(2.seconds) &&
        serviceV2.pingSince(2.seconds)
    }

    When("The v2 frontend becomes healthy")
    frontendV2.state = true

    Then("The deployment can be finished. All v1 apps are destroyed and all v2 apps are healthy.")
    waitForChange(upgrade)
    List(dbV1, serviceV1, frontendV1).foreach(_.pinged = false)
    validFor("all v2 apps are alive", 15.seconds) {
      !dbV1.pinged && !serviceV1.pinged && !frontendV1.pinged &&
        dbV2.pingSince(2.seconds) && serviceV2.pingSince(2.seconds) && frontendV2.pingSince(2.seconds)
    }
  }
}
