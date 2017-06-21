package mesosphere.marathon
package integration

import java.util.concurrent.atomic.AtomicInteger

import akka.http.scaladsl.model.DateTime
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.{ EmbeddedMarathonTest, IntegrationHealthCheck }
import mesosphere.marathon.raml.{ App, GroupUpdate, UpgradeStrategy }
import mesosphere.marathon.state.{ Group, PathId }

import scala.concurrent.duration._

@IntegrationTest
class GroupDeployIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  import PathId._

  //clean up state before running the test case
  before(cleanUp())

  val appIdCount = new AtomicInteger()
  val groupIdCount = new AtomicInteger()

  def nextAppId(): String = s"app-${appIdCount.getAndIncrement()}"
  def nextGroupId(): PathId = s"group-${groupIdCount.getAndIncrement()}".toRootTestPath

  def temporaryGroup(testCode: (PathId) => Any): Unit = {
    val gid = nextGroupId()
    try {
      testCode(gid)
    } finally {
      marathon.deleteGroup(gid, force = true)
    }
  }

  "GroupDeployment" should {
    "create empty group successfully" in {
      Given("A group which does not exist in marathon")
      val group = Group.emptyUpdate(nextGroupId())

      When("The group gets created")
      val result = marathon.createGroup(group)

      Then("The group is created. A success event for this group is send.")
      result should be(Created)
      waitForDeployment(result)
    }

    "update empty group successfully" in {
      Given("An existing group")
      val name = "test2".toRootTestPath
      val group = Group.emptyUpdate(name)
      waitForDeployment(marathon.createGroup(group))

      When("The group gets updated")
      val dependencies = Set("/test".toTestPath.toString)
      waitForDeployment(marathon.updateGroup(name, group.copy(dependencies = Some(dependencies))))

      Then("The group is updated")
      val result = marathon.group("test2".toRootTestPath)
      result should be(OK)
      result.value.dependencies should be(dependencies)
    }

    "deleting an existing group gives a 200 http response" in {
      Given("An existing group")
      val group = Group.emptyUpdate(nextGroupId())
      waitForDeployment(marathon.createGroup(group))

      When("The group gets deleted")
      val result = marathon.deleteGroup(PathId(group.id.get))
      waitForDeployment(result)

      Then("The group is deleted")
      result should be(OK)
      // only expect the test base group itself
      marathon.listGroupsInBaseGroup.value.filter { group => group.id != testBasePath } should be('empty)
    }

    "delete a non existing group should give a 404 http response" in {
      When("A non existing group is deleted")
      val result = marathon.deleteGroup("does_not_exist".toRootTestPath)

      Then("We get a 404 http response code")
      result should be(NotFound)
    }

    "create a group with applications to start" in {
      val id = "test".toRootTestPath
      val appId = id / nextAppId()

      Given(s"A group with one application with id $appId")
      val app = appProxy(appId, "v1", 2, healthCheck = None)
      val group = GroupUpdate(Some("/test".toRootTestPath.toString), apps = Some(Set(app)))

      When("The group is created")
      waitForDeployment(marathon.createGroup(group))

      Then("A success event is send and the application has been started")
      val tasks = waitForTasks(PathId(app.id), app.instances)
      tasks should have size 2
    }

    "update a group with applications to restart" in {
      val id = nextGroupId()
      val appId = id / nextAppId()

      Given(s"A group with one application started with id $appId")
      val app1V1 = appProxy(appId, "v1", 2, healthCheck = None)
      waitForDeployment(marathon.createGroup(GroupUpdate(Some(id.toString), Some(Set(app1V1)))))
      waitForTasks(PathId(app1V1.id), app1V1.instances)

      When("The group is updated, with a changed application")
      val app1V2 = appProxy(appId, "v2", 2, healthCheck = None)
      waitForDeployment(marathon.updateGroup(id, GroupUpdate(Some(id.toString), Some(Set(app1V2)))))

      Then("A success event is send and the application has been started")
      waitForTasks(PathId(app1V2.id), app1V2.instances)
    }

    "update a group with the same application so no restart is triggered" in {
      val id = nextGroupId()
      val appId = id / nextAppId()

      Given(s"A group with one application started with id $appId")
      val app1V1 = appProxy(appId, "v1", 2, healthCheck = None)
      waitForDeployment(marathon.createGroup(GroupUpdate(Some(id.toString), Some(Set(app1V1)))))
      waitForTasks(PathId(app1V1.id), app1V1.instances)
      val tasks = marathon.tasks(appId)

      When("The group is updated, with the same application")
      waitForDeployment(marathon.updateGroup(id, GroupUpdate(Some(id.toString), Some(Set(app1V1)))))

      Then("There is no deployment and all tasks still live")
      marathon.listDeploymentsForBaseGroup().value should be ('empty)
      marathon.tasks(appId).value.toSet should be(tasks.value.toSet)
    }

    "create a group with application with health checks" in {
      val id = nextGroupId()
      val appId = id / nextAppId()

      Given(s"A group with one application with id $appId")
      val proxy = appProxy(appId, "v1", 1)
      val group = GroupUpdate(Some(id.toString), Some(Set(proxy)))

      When("The group is created")
      val create = marathon.createGroup(group)

      Then("A success event is send and the application has been started")
      waitForDeployment(create)
    }

    "upgrade a group with application with health checks" in {
      val id = nextGroupId()
      val appId = id / nextAppId()

      Given(s"A group with one application with id $appId")
      val proxy = appProxy(appId, "v1", 1)
      val group = GroupUpdate(Some(id.toString), Some(Set(proxy)))
      waitForDeployment(marathon.createGroup(group))
      val check = registerAppProxyHealthCheck(PathId(proxy.id), "v1", state = true)

      When("The group is updated")
      check.afterDelay(1.second, state = false)
      check.afterDelay(3.seconds, state = true)
      val update = marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 1)))))

      Then("A success event is send and the application has been started")
      waitForDeployment(update)
    }

    "rollback from an upgrade of group" in {
      val gid = nextGroupId()
      val appId = gid / nextAppId()

      Given(s"A group with one application with id $appId")
      val proxy = appProxy(appId, "v1", 2)
      val group = GroupUpdate(Some(gid.toString), Some(Set(proxy)))
      val create = marathon.createGroup(group)
      waitForDeployment(create)
      waitForTasks(PathId(proxy.id), proxy.instances)
      val v1Checks = registerAppProxyHealthCheck(appId, "v1", state = true)

      When("The group is updated")
      waitForDeployment(marathon.updateGroup(gid, group.copy(apps = Some(Set(appProxy(appId, "v2", 2))))))

      Then("The new version is deployed")
      val v2Checks = registerAppProxyHealthCheck(appId, "v2", state = true)
      eventually {
        v2Checks.pinged.get should be(true) withClue "v2 apps did not come up"
      }

      When("A rollback to the first version is initiated")
      v1Checks.pinged.set(false)
      waitForDeployment(marathon.rollbackGroup(gid, create.value.version))

      Then("The rollback will be performed and the old version is available")
      eventually {
        v1Checks.pinged.get should be(true) withClue "v1 apps did not come up again"
      }
    }

    "during Deployment the defined minimum health capacity is never undershot" in {
      val id = nextGroupId()
      val appId = id / nextAppId()

      Given(s"A group with one application with id $appId")
      val proxy = appProxy(appId, "v1", 2).copy(upgradeStrategy = Some(UpgradeStrategy(1, 1)))
      val group = GroupUpdate(Some(id.toString), Some(Set(proxy)))
      val create = marathon.createGroup(group)
      waitForDeployment(create)
      waitForTasks(appId, proxy.instances)
      val v1Check = registerAppProxyHealthCheck(appId, "v1", state = true)

      When("The new application is not healthy")
      val v2Check = registerAppProxyHealthCheck(appId, "v2", state = false) //will always fail
      val update = marathon.updateGroup(id, group.copy(apps = Some(Set(appProxy(appId, "v2", 2)))))

      Then("All v1 applications are kept alive")
      v1Check.pinged.set(false)
      eventually {
        v1Check.pinged.get should be(true) withClue "v1 are not alive"
      }

      When("The new application becomes healthy")
      v2Check.state = true //make v2 healthy, so the app can be cleaned
      waitForDeployment(update)
    }

    "An upgrade in progress cannot be interrupted without force" in temporaryGroup { gid =>
      val appId = gid / nextAppId()

      Given(s"A group with one application with id $appId with an upgrade in progress")
      val proxy = appProxy(appId, "v1", 2)
      val group = GroupUpdate(Some(gid.toString), Some(Set(proxy)))
      val create = marathon.createGroup(group)
      waitForDeployment(create)
      registerAppProxyHealthCheck(appId, "v2", state = false) //will always fail
      marathon.updateGroup(gid, group.copy(apps = Some(Set(appProxy(appId, "v2", 2)))))

      When("Another upgrade is triggered, while the old one is not completed")
      val result = marathon.updateGroup(gid, group.copy(apps = Some(Set(appProxy(appId, "v3", 2)))))

      Then("An error is indicated")
      result should be (Conflict)
      waitForEvent("group_change_failed")

      When("Another upgrade is triggered with force, while the old one is not completed")
      val force = marathon.updateGroup(gid, group.copy(apps = Some(Set(appProxy(appId, "v4", 2)))), force = true)

      Then("The update is performed")
      waitForDeployment(force)
    }

    "A group with a running deployment can not be deleted without force" in temporaryGroup{ gid =>
      val appId = gid / nextAppId()

      Given(s"A group with one application with id $appId with an upgrade in progress")
      val proxy = appProxy(appId, "v1", 2)
      registerAppProxyHealthCheck(appId, "v1", state = false) //will always fail
      val group = GroupUpdate(Some(gid.toString), Some(Set(proxy)))
      marathon.createGroup(group)

      When("Delete the group, while the deployment is in progress")
      val deleteResult = marathon.deleteGroup(gid)

      Then("An error is indicated")
      deleteResult should be(Conflict)
      waitForEvent("group_change_failed")

      When("Delete is triggered with force, while the deployment is not completed")
      val force = marathon.deleteGroup(gid, force = true)
      force.success should be(true) withClue (s"Could not force delete $gid: Response: code=${force.code} body=${force.entityString}")

      Then("The delete is performed")
      waitForDeployment(force)
    }

    "Groups with Applications with circular dependencies can not get deployed" in {
      val gid = nextGroupId()

      Given(s"A group with id $gid with 3 circular dependent applications")
      val db = appProxy(gid / "db", "v1", 1, dependencies = Set(gid / "frontend1"))
      val service = appProxy(gid / "service", "v1", 1, dependencies = Set(db.id.toPath))
      val frontend = appProxy(gid / "frontend1", "v1", 1, dependencies = Set(service.id.toPath))
      val group = GroupUpdate(Option(gid.toString), Option(Set(db, service, frontend)))

      When("The group gets posted")
      val result = marathon.createGroup(group)

      Then("An unsuccessful response has been posted, with an error indicating cyclic dependencies")
      result.success should be(false) withClue s"Response code is ${result.code}: ${result.entityString}"

      val errors = (result.entityJson \ "details" \\ "errors").flatMap(_.as[Seq[String]])
      errors.find(_.contains("cyclic dependencies")) shouldBe defined withClue s"""errors "$errors" did not contain "cyclic dependencies" error."""
    }

    "Applications with dependencies get deployed in the correct order" in temporaryGroup { gid =>

      Given(s"A group with id $gid with 3 dependent applications")
      val db = appProxy(gid / "db", "v1", 1)
      val service = appProxy(gid / "service", "v1", 1, dependencies = Set(db.id.toPath))
      val frontend = appProxy(gid / "frontend1", "v1", 1, dependencies = Set(service.id.toPath))
      val group = GroupUpdate(Option(gid.toString), Option(Set(db, service, frontend)))

      When("The group gets deployed")
      var ping = Map.empty[String, DateTime]
      def storeFirst(health: IntegrationHealthCheck): Unit = {
        if (!ping.contains(health.appId.toString)) ping += health.appId.toString -> DateTime.now
      }
      registerAppProxyHealthCheck(PathId(db.id), "v1", state = true).withHealthAction(storeFirst)
      registerAppProxyHealthCheck(PathId(service.id), "v1", state = true).withHealthAction(storeFirst)
      registerAppProxyHealthCheck(PathId(frontend.id), "v1", state = true).withHealthAction(storeFirst)

      val response = marathon.createGroup(group)
      response.success should be(true) withClue (s"Could create group $gid: Response: code=${response.code} body=${response.entityString}")
      waitForDeployment(response)

      Then("The correct order is maintained")
      ping should have size 3
      ping(db.id) should be < ping(service.id) withClue s"database was deployed at ${ping(db.id)} and service at ${ping(service.id)}"
      ping(service.id) should be < ping(frontend.id) withClue s"service was deployed at ${ping(service.id)} and frontend at ${ping(frontend.id)}"
    }

    "Groups with dependencies get deployed in the correct order" in temporaryGroup { gid =>

      Given(s"A group with id $gid with 3 dependent applications")
      val db = appProxy(gid / "db/db1", "v1", 1)
      val service = appProxy(gid / "service/service1", "v1", 1)
      val frontend = appProxy(gid / "frontend/frontend1", "v1", 1)
      val group = GroupUpdate(
        Option(gid.toString),
        Option(Set.empty[App]),
        Option(Set(
          GroupUpdate(Some("db"), apps = Some(Set(db))),
          GroupUpdate(Some("service"), apps = Some(Set(service))).copy(dependencies = Some(Set((gid / "db").toString))),
          GroupUpdate(Some("frontend"), apps = Some(Set(frontend))).copy(dependencies = Some(Set((gid / "service").toString)))
        ))
      )

      When("The group gets deployed")
      var ping = Map.empty[String, DateTime]
      def storeFirst(health: IntegrationHealthCheck): Unit = {
        if (!ping.contains(health.appId.toString)) ping += health.appId.toString -> DateTime.now
      }
      registerAppProxyHealthCheck(PathId(db.id), "v1", state = true).withHealthAction(storeFirst)
      registerAppProxyHealthCheck(PathId(service.id), "v1", state = true).withHealthAction(storeFirst)
      registerAppProxyHealthCheck(PathId(frontend.id), "v1", state = true).withHealthAction(storeFirst)
      waitForDeployment(marathon.createGroup(group))

      Then("The correct order is maintained")
      ping should have size 3
      ping(db.id) should be < ping(service.id) withClue s"database was deployed at ${ping(db.id)} and service at ${ping(service.id)}"
      ping(service.id) should be < ping(frontend.id) withClue s"service was deployed at ${ping(service.id)} and frontend at ${ping(frontend.id)}"
    }
  }
}
