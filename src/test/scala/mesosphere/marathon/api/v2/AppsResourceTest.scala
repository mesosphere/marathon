package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletRequest
import javax.validation.ConstraintViolationException

import akka.event.EventStream
import mesosphere.marathon._
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.api.{ JsonTestHelper, TaskKiller }
import mesosphere.marathon.core.appinfo.AppInfo.Embed
import mesosphere.marathon.core.appinfo.{ AppInfo, AppInfoService, TaskCounts }
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.Mockito
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.{ JsNumber, Json }

import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.language.postfixOps
import scala.concurrent.duration._

class AppsResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  import mesosphere.marathon.api.v2.json.Formats._

  test("Create a new app successfully") {
    Given("An app and group")
    val req = mock[HttpServletRequest]
    val app = V2AppDefinition(id = PathId("/app"), cmd = Some("cmd"), version = clock.now())
    val group = Group(PathId("/"), Set(app.toAppDefinition))
    val plan = DeploymentPlan(group, group)
    val body = Json.stringify(Json.toJson(app)).getBytes("UTF-8")
    groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
    groupManager.rootGroup() returns Future.successful(group)

    When("The create request is made")
    clock += 5.seconds
    val response = appsResource.create(req, body, force = false)

    Then("It is successful")
    response.getStatus should be(201)

    And("the JSON is as expected, including a newly generated version")
    val expected = AppInfo(
      app.copy(version = clock.now()).toAppDefinition,
      maybeTasks = Some(immutable.Seq.empty),
      maybeCounts = Some(TaskCounts.zero),
      maybeDeployments = Some(immutable.Seq(Identifiable(plan.id)))
    )
    JsonTestHelper.assertThatJsonString(response.getEntity.asInstanceOf[String]).correspondsToJsonOf(expected)
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

  test("Index has counts and deployments by default (regression for #2171)") {
    Given("An app and group")
    val req = mock[HttpServletRequest]
    val app = AppDefinition(id = PathId("/app"), cmd = Some("foo"))
    val expectedEmbeds: Set[Embed] = Set(Embed.Counts, Embed.Deployments)
    val appInfo = AppInfo(app, maybeDeployments = Some(Seq(Identifiable("deployment-123"))), maybeCounts = Some(TaskCounts(1, 2, 3, 4)))
    appInfoService.queryAll(any, eq(expectedEmbeds)) returns Future.successful(Seq(appInfo))

    When("The the index is fetched without any filters")
    val response = appsResource.index(null, null, null, new java.util.HashSet())

    Then("The response holds counts and deployments")
    val appJson = Json.parse(response)
    (appJson \ "apps" \\ "deployments" head) should be (Json.arr(Json.obj("id" -> "deployment-123")))
    (appJson \ "apps" \\ "tasksStaged" head) should be (JsNumber(1))
  }

  test("Search apps can be filtered") {
    val app1 = AppDefinition(id = PathId("/app/service-a"), cmd = Some("party hard"), labels = Map("a" -> "1", "b" -> "2"))
    val app2 = AppDefinition(id = PathId("/app/service-b"), cmd = Some("work hard"), labels = Map("a" -> "1", "b" -> "3"))
    val apps = Set(app1, app2)

    def search(cmd: Option[String], id: Option[String], label: Option[String]): Set[AppDefinition] = {
      val selector = appsResource.search(cmd, id, label)
      apps.filter(selector.matches(_))
    }

    search(cmd = None, id = None, label = None) should be(Set(app1, app2))
    search(cmd = Some(""), id = None, label = None) should be(Set(app1, app2))
    search(cmd = Some("party"), id = None, label = None) should be(Set(app1))
    search(cmd = Some("work"), id = None, label = None) should be(Set(app2))
    search(cmd = Some("hard"), id = None, label = None) should be(Set(app1, app2))
    search(cmd = Some("none"), id = None, label = None) should be(Set.empty)

    search(cmd = None, id = Some("app"), label = None) should be(Set(app1, app2))
    search(cmd = None, id = Some("service-a"), label = None) should be(Set(app1))
    search(cmd = Some("party"), id = Some("app"), label = None) should be(Set(app1))
    search(cmd = Some("work"), id = Some("app"), label = None) should be(Set(app2))
    search(cmd = Some("hard"), id = Some("service-a"), label = None) should be(Set(app1))
    search(cmd = Some(""), id = Some(""), label = None) should be(Set(app1, app2))

    search(cmd = None, id = None, label = Some("b==2")) should be(Set(app1))
    search(cmd = Some("party"), id = Some("app"), label = Some("a==1")) should be(Set(app1))
    search(cmd = Some("work"), id = Some("app"), label = Some("a==1")) should be(Set(app2))
    search(cmd = Some("hard"), id = Some("service-a"), label = Some("a==1")) should be(Set(app1))
    search(cmd = Some(""), id = Some(""), label = Some("")) should be(Set(app1, app2))
  }

  var clock: ConstantClock = _
  var eventBus: EventStream = _
  var service: MarathonSchedulerService = _
  var taskTracker: TaskTracker = _
  var taskKiller: TaskKiller = _
  var healthCheckManager: HealthCheckManager = _
  var taskFailureRepo: TaskFailureRepository = _
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var appInfoService: AppInfoService = _
  var appsResource: AppsResource = _

  before {
    clock = ConstantClock()
    eventBus = mock[EventStream]
    service = mock[MarathonSchedulerService]
    taskTracker = mock[TaskTracker]
    taskKiller = mock[TaskKiller]
    healthCheckManager = mock[HealthCheckManager]
    taskFailureRepo = mock[TaskFailureRepository]
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]
    appInfoService = mock[AppInfoService]
    appsResource = new AppsResource(
      clock,
      eventBus,
      mock[AppTasksResource],
      service,
      appInfoService,
      config,
      groupManager
    )
  }
}
