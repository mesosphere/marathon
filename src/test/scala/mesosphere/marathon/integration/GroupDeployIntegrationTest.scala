package mesosphere.marathon
package integration

import java.util.concurrent.atomic.AtomicInteger
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.api.v2.json.GroupUpdate
import mesosphere.marathon.integration.setup.{ EmbeddedMarathonTest, IntegrationHealthCheck, WaitTestSupport }
import mesosphere.marathon.state.{ AppDefinition, PathId, UpgradeStrategy }
import org.apache.http.HttpStatus
import spray.http.DateTime

import scala.concurrent.duration._

@IntegrationTest
class GroupDeployIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  //clean up state before running the test case
  before(cleanUp())

  val appIdCount = new AtomicInteger()
  val groupIdCount = new AtomicInteger()

  def nextAppId(): String = s"app-${appIdCount.getAndIncrement()}"
  def nextGroupId(): PathId = s"group-${groupIdCount.getAndIncrement()}".toRootTestPath

  "GroupDeployment" should {
    "create empty group successfully" in {
      Given("A group which does not exist in marathon")
      val group = GroupUpdate.empty(nextGroupId())

      When("The group gets created")
      val result = marathon.createGroup(group)

      Then("The group is created. A success event for this group is send.")
      result.code should be(201) //created
      val event = waitForDeployment(result)
    }

    "update empty group successfully" in {
      Given("An existing group")
      val name = "test2".toRootTestPath
      val group = GroupUpdate.empty(name)
      val dependencies = Set("/test".toTestPath)
      waitForDeployment(marathon.createGroup(group))

      When("The group gets updated")
      waitForDeployment(marathon.updateGroup(name, group.copy(dependencies = Some(dependencies))))

      Then("The group is updated")
      val result = marathon.group("test2".toRootTestPath)
      result.code should be(200)
      result.value.dependencies should be(dependencies)
    }

    "deleting an existing group gives a 200 http response" in {
      Given("An existing group")
      val group = GroupUpdate.empty(nextGroupId())
      waitForDeployment(marathon.createGroup(group))

      When("The group gets deleted")
      val result = marathon.deleteGroup(group.id.get)
      waitForDeployment(result)

      Then("The group is deleted")
      result.code should be(200)
      // only expect the test base group itself
      marathon.listGroupsInBaseGroup.value.filter { group => group.id != testBasePath } should be('empty)
    }

    "delete a non existing group should give a 404 http response" in {
      When("A non existing group is deleted")
      val result = marathon.deleteGroup("does_not_exist".toRootTestPath)

      Then("We get a 404 http response code")
      result.code should be(404)
    }

    "create a group with applications to start" in {
      Given("A group with one application")
      val id = "test".toRootTestPath
      val appId = id / nextAppId()
      val app = appProxy(appId, "v1", 2, healthCheck = None)
      val group = GroupUpdate("/test".toRootTestPath, Set(app))

      When("The group is created")
      waitForDeployment(marathon.createGroup(group))

      Then("A success event is send and the application has been started")
      val tasks = waitForTasks(app.id, app.instances)
      tasks should have size 2
    }

    "update a group with applications to restart" in {
      Given("A group with one application started")
      val id = nextGroupId()
      val appId = id / nextAppId()
      val app1V1 = appProxy(appId, "v1", 2, healthCheck = None)
      waitForDeployment(marathon.createGroup(GroupUpdate(id, Set(app1V1))))
      waitForTasks(app1V1.id, app1V1.instances)

      When("The group is updated, with a changed application")
      val app1V2 = appProxy(appId, "v2", 2, healthCheck = None)
      waitForDeployment(marathon.updateGroup(id, GroupUpdate(id, Set(app1V2))))

      Then("A success event is send and the application has been started")
      waitForTasks(app1V2.id, app1V2.instances)
    }

    "update a group with the same application so no restart is triggered" in {
      Given("A group with one application started")
      val id = nextGroupId()
      val appId = id / nextAppId()
      val app1V1 = appProxy(appId, "v1", 2, healthCheck = None)
      waitForDeployment(marathon.createGroup(GroupUpdate(id, Set(app1V1))))
      waitForTasks(app1V1.id, app1V1.instances)
      val tasks = marathon.tasks(appId)

      When("The group is updated, with the same application")
      waitForDeployment(marathon.updateGroup(id, GroupUpdate(id, Set(app1V1))))

      Then("There is no deployment and all tasks still live")
      marathon.listDeploymentsForBaseGroup().value should be ('empty)
      marathon.tasks(appId).value.toSet should be(tasks.value.toSet)
    }

    "create a group with application with health checks" in {
      Given("A group with one application")
      val id = nextGroupId()
      val appId = id / nextAppId()
      val proxy = appProxy(appId, "v1", 1)
      val group = GroupUpdate(id, Set(proxy))

      When("The group is created")
      val create = marathon.createGroup(group)

      Then("A success event is send and the application has been started")
      waitForDeployment(create)
    }

    "upgrade a group with application with health checks" in {
      Given("A group with one application")
      val id = nextGroupId()
      val appId = id / nextAppId()
      val proxy = appProxy(appId, "v1", 1)
      val group = GroupUpdate(id, Set(proxy))
      waitForDeployment(marathon.createGroup(group))
      val check = appProxyCheck(proxy.id, "v1", state = true)

      When("The group is updated")
      check.afterDelay(1.second, state = false)
      check.afterDelay(3.seconds, state = true)
      val update = marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 1)))))

      Then("A success event is send and the application has been started")
      waitForDeployment(update)
    }

    "rollback from an upgrade of group" in {
      Given("A group with one application")
      val gid = nextGroupId()
      val appId = gid / nextAppId()
      val proxy = appProxy(appId, "v1", 2)
      val group = GroupUpdate(gid, Set(proxy))
      val create = marathon.createGroup(group)
      waitForDeployment(create)
      waitForTasks(proxy.id, proxy.instances)
      val v1Checks = appProxyCheck(appId, "v1", state = true)

      When("The group is updated")
      waitForDeployment(marathon.updateGroup(gid, group.copy(apps = Some(Set(appProxy(appId, "v2", 2))))))

      Then("The new version is deployed")
      val v2Checks = appProxyCheck(appId, "v2", state = true)
      WaitTestSupport.validFor("all v2 apps are available", 10.seconds) { v2Checks.pingSince(2.seconds) }

      When("A rollback to the first version is initiated")
      waitForDeployment(marathon.rollbackGroup(gid, create.value.version), 120.seconds)

      Then("The rollback will be performed and the old version is available")
      v1Checks.healthy
      WaitTestSupport.validFor("all v1 apps are available", 10.seconds) { v1Checks.pingSince(2.seconds) }
    }

    "during Deployment the defined minimum health capacity is never undershot" in {
      Given("A group with one application")
      val id = nextGroupId()
      val appId = id / nextAppId()
      val proxy = appProxy(appId, "v1", 2).copy(upgradeStrategy = UpgradeStrategy(1))
      val group = GroupUpdate(id, Set(proxy))
      val create = marathon.createGroup(group)
      waitForDeployment(create)
      waitForTasks(appId, proxy.instances)
      val v1Check = appProxyCheck(appId, "v1", state = true)

      When("The new application is not healthy")
      val v2Check = appProxyCheck(appId, "v2", state = false) //will always fail
      val update = marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 2)))))

      Then("All v1 applications are kept alive")
      v1Check.healthy
      WaitTestSupport.validFor("all v1 apps are always available", 15.seconds) { v1Check.pingSince(3.seconds) }

      When("The new application becomes healthy")
      v2Check.state = true //make v2 healthy, so the app can be cleaned
      waitForDeployment(update)
    }

    "An upgrade in progress cannot be interrupted without force" in {
      Given("A group with one application with an upgrade in progress")
      val id = nextGroupId()
      val appId = id / nextAppId()
      val proxy = appProxy(appId, "v1", 2)
      val group = GroupUpdate(id, Set(proxy))
      val create = marathon.createGroup(group)
      waitForDeployment(create)
      appProxyCheck(appId, "v2", state = false) //will always fail
      marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 2)))))

      When("Another upgrade is triggered, while the old one is not completed")
      val result = marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v3", 2)))))

      Then("An error is indicated")
      result.code should be (HttpStatus.SC_CONFLICT)
      waitForEvent("group_change_failed")

      When("Another upgrade is triggered with force, while the old one is not completed")
      val force = marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v4", 2)))), force = true)

      Then("The update is performed")
      waitForDeployment(force)
    }

    "A group with a running deployment can not be deleted without force" in {
      Given("A group with one application with an upgrade in progress")
      val id = nextGroupId()
      val appId = id / nextAppId()
      val proxy = appProxy(appId, "v1", 2)
      appProxyCheck(appId, "v1", state = false) //will always fail
      val group = GroupUpdate(id, Set(proxy))
      val create = marathon.createGroup(group)

      When("Delete the group, while the deployment is in progress")
      val deleteResult = marathon.deleteGroup(id)

      Then("An error is indicated")
      deleteResult.code should be (HttpStatus.SC_CONFLICT)
      waitForEvent("group_change_failed")

      When("Delete is triggered with force, while the deployment is not completed")
      val force = marathon.deleteGroup(id, force = true)

      Then("The delete is performed")
      waitForDeployment(force)
    }

    "Groups with Applications with circular dependencies can not get deployed" in {
      Given("A group with 3 circular dependent applications")
      val gid = nextGroupId()
      val db = appProxy(gid / "db", "v1", 1, dependencies = Set(gid / "frontend1"))
      val service = appProxy(gid / "service", "v1", 1, dependencies = Set(db.id))
      val frontend = appProxy(gid / "frontend1", "v1", 1, dependencies = Set(service.id))
      val group = GroupUpdate(gid, Set(db, service, frontend))

      When("The group gets posted")
      val result = marathon.createGroup(group)

      Then("An unsuccessful response has been posted, with an error indicating cyclic dependencies")
      val errors = (result.entityJson \ "details" \\ "errors").flatMap(_.as[Seq[String]])
      errors.find(_.contains("cyclic dependencies")) shouldBe defined
    }

    "Applications with dependencies get deployed in the correct order" in {
      Given("A group with 3 dependent applications")
      val gid = nextGroupId()
      val db = appProxy(gid / "db", "v1", 1)
      val service = appProxy(gid / "service", "v1", 1, dependencies = Set(db.id))
      val frontend = appProxy(gid / "frontend1", "v1", 1, dependencies = Set(service.id))
      val group = GroupUpdate(gid, Set(db, service, frontend))

      When("The group gets deployed")
      var ping = Map.empty[PathId, DateTime]
      def storeFirst(health: IntegrationHealthCheck): Unit = {
        if (!ping.contains(health.appId)) ping += health.appId -> DateTime.now
      }
      val dbHealth = appProxyCheck(db.id, "v1", state = true).withHealthAction(storeFirst)
      val serviceHealth = appProxyCheck(service.id, "v1", state = true).withHealthAction(storeFirst)
      val frontendHealth = appProxyCheck(frontend.id, "v1", state = true).withHealthAction(storeFirst)
      waitForDeployment(marathon.createGroup(group))

      Then("The correct order is maintained")
      ping should have size 3
      ping(db.id) should be < ping(service.id)
      ping(service.id) should be < ping(frontend.id)
    }

    "Groups with dependencies get deployed in the correct order" in {
      Given("A group with 3 dependent applications")
      val gid = nextGroupId()
      val db = appProxy(gid / "db/db1", "v1", 1)
      val service = appProxy(gid / "service/service1", "v1", 1)
      val frontend = appProxy(gid / "frontend/frontend1", "v1", 1)
      val group = GroupUpdate(
        gid,
        Set.empty[AppDefinition],
        Set(
          GroupUpdate(PathId("db"), apps = Set(db)),
          GroupUpdate(PathId("service"), apps = Set(service)).copy(dependencies = Some(Set(gid / "db"))),
          GroupUpdate(PathId("frontend"), apps = Set(frontend)).copy(dependencies = Some(Set(gid / "service")))
        )
      )

      When("The group gets deployed")
      var ping = Map.empty[PathId, DateTime]
      def storeFirst(health: IntegrationHealthCheck): Unit = {
        if (!ping.contains(health.appId)) ping += health.appId -> DateTime.now
      }
      val dbHealth = appProxyCheck(db.id, "v1", state = true).withHealthAction(storeFirst)
      val serviceHealth = appProxyCheck(service.id, "v1", state = true).withHealthAction(storeFirst)
      val frontendHealth = appProxyCheck(frontend.id, "v1", state = true).withHealthAction(storeFirst)
      waitForDeployment(marathon.createGroup(group))

      Then("The correct order is maintained")
      ping should have size 3
      ping(db.id) should be < ping(service.id)
      ping(service.id) should be < ping(frontend.id)
    }
  }
}
