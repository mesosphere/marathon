package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletRequest
import javax.validation.ConstraintViolationException

import akka.event.EventStream
import mesosphere.marathon._
import mesosphere.marathon.api.TaskKiller
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.Mockito
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.Json

import scala.concurrent.Future

class AppsResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  import mesosphere.marathon.api.v2.json.Formats._

  test("Create a new app successfully") {
    Given("An app and group")
    val req = mock[HttpServletRequest]
    val app = V2AppDefinition(id = PathId("/app"), cmd = Some("cmd"))
    val group = Group(PathId("/"), Set(app.toAppDefinition))
    val plan = DeploymentPlan(group, group)
    val body = Json.stringify(Json.toJson(app)).getBytes("UTF-8")
    groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
    groupManager.rootGroup() returns Future.successful(group)

    When("The create request is made")
    val response = appsResource.create(req, body, false)

    Then("It is successful")
    response.getStatus should be(201)
    response.getEntity should be(app)
  }

  test("Create a new app fails with Validation errors") {
    Given("An app with validation errors")
    val req = mock[HttpServletRequest]
    val app = V2AppDefinition(id = PathId("/app"))
    val group = Group(PathId("/"), Set(app.toAppDefinition))
    val plan = DeploymentPlan(group, group)
    val body = Json.stringify(Json.toJson(app)).getBytes("UTF-8")
    groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)

    Then("A constraint violation exception is thrown")
    intercept[ConstraintViolationException] { appsResource.create(req, body, false) }
  }

  test("Replace an existing application") {
    Given("An app and group")
    val req = mock[HttpServletRequest]
    val app = AppDefinition(id = PathId("/app"), cmd = Some("foo"))
    val group = Group(PathId("/"), Set(app))
    val plan = DeploymentPlan(group, group)
    val body = """{ "cmd": "bla" }""".getBytes("UTF-8")
    groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)

    When("The application is updates")
    val response = appsResource.replace(req, app.id.toString, false, body)

    Then("The application is updated")
    response.getStatus should be(200)
  }

  test("Restart an existing app") {
    val app = AppDefinition(id = PathId("/app"))
    val group = Group(PathId("/"), Set(app))
    val plan = DeploymentPlan(group, group)
    service.deploy(any, any) returns Future.successful(())

    groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
    val response = appsResource.restart(app.id.toString, force = true)
    response.getStatus should be(200)
  }

  test("Restart a non existing app will fail") {
    val missing = PathId("/app")
    groupManager.updateApp(any, any, any, any, any) returns Future.failed(new UnknownAppException(missing))
    intercept[UnknownAppException] { appsResource.restart(missing.toString, force = true) }
  }

  test("Search apps can be filtered") {
    val app1 = AppDefinition(id = "/app/service-a".toRootPath, cmd = Some("party hard"), labels = Map("a" -> "1", "b" -> "2"))
    val app2 = AppDefinition(id = "/app/service-b".toRootPath, cmd = Some("work hard"), labels = Map("a" -> "1", "b" -> "3"))
    val group = Group(id = PathId.empty, apps = Set(app1, app2))
    groupManager.rootGroup() returns Future.successful(group)

    appsResource.search(None, None, None).toSet should be(Set(app1, app2))
    appsResource.search(Some(""), None, None).toSet should be(Set(app1, app2))
    appsResource.search(Some("party"), None, None).toSet should be(Set(app1))
    appsResource.search(Some("work"), None, None).toSet should be(Set(app2))
    appsResource.search(Some("hard"), None, None).toSet should be(Set(app1, app2))
    appsResource.search(Some("none"), None, None).toSet should be(Set.empty)

    appsResource.search(None, Some("app"), None).toSet should be(Set(app1, app2))
    appsResource.search(None, Some("service-a"), None).toSet should be(Set(app1))
    appsResource.search(Some("party"), Some("app"), None).toSet should be(Set(app1))
    appsResource.search(Some("work"), Some("app"), None).toSet should be(Set(app2))
    appsResource.search(Some("hard"), Some("service-a"), None).toSet should be(Set(app1))
    appsResource.search(Some(""), Some(""), None).toSet should be(Set(app1, app2))

    appsResource.search(None, None, Some("b==2")).toSet should be(Set(app1))
    appsResource.search(Some("party"), Some("app"), Some("a==1")).toSet should be(Set(app1))
    appsResource.search(Some("work"), Some("app"), Some("a==1")).toSet should be(Set(app2))
    appsResource.search(Some("hard"), Some("service-a"), Some("a==1")).toSet should be(Set(app1))
    appsResource.search(Some(""), Some(""), Some("")).toSet should be(Set(app1, app2))
  }

  var eventBus: EventStream = _
  var service: MarathonSchedulerService = _
  var taskTracker: TaskTracker = _
  var taskKiller: TaskKiller = _
  var healthCheckManager: HealthCheckManager = _
  var taskFailureRepo: TaskFailureRepository = _
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var appsResource: AppsResource = _

  before {
    eventBus = mock[EventStream]
    service = mock[MarathonSchedulerService]
    taskTracker = mock[TaskTracker]
    taskKiller = mock[TaskKiller]
    healthCheckManager = mock[HealthCheckManager]
    taskFailureRepo = mock[TaskFailureRepository]
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]
    appsResource = new AppsResource(
      eventBus,
      service,
      taskTracker,
      taskKiller,
      healthCheckManager,
      taskFailureRepo,
      config,
      groupManager
    )
  }
}
