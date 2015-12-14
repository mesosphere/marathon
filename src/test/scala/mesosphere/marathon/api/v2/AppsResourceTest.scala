package mesosphere.marathon.api.v2

import java.util
import javax.validation.ConstraintViolationException

import akka.event.EventStream
import mesosphere.marathon._
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.api.{ JsonTestHelper, TaskKiller, TestAuthFixture }
import mesosphere.marathon.core.appinfo.AppInfo.Embed
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ TaskTracker, TaskTrackerImpl, TaskTrackerImpl$ }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentPlan
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.{ JsNumber, Json }

import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.language.postfixOps
import scala.concurrent.duration._
import PathId._

class AppsResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  import mesosphere.marathon.api.v2.json.Formats._

  test("Create a new app successfully") {
    Given("An app and group")
    val app = V2AppDefinition(id = PathId("/app"), cmd = Some("cmd"), version = clock.now())
    val group = Group(PathId("/"), Set(app.toAppDefinition))
    val plan = DeploymentPlan(group, group)
    val body = Json.stringify(Json.toJson(app)).getBytes("UTF-8")
    groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
    groupManager.rootGroup() returns Future.successful(group)

    When("The create request is made")
    clock += 5.seconds
    val response = appsResource.create(body, force = false, auth.request, auth.response)

    Then("It is successful")
    response.getStatus should be(201)

    And("the JSON is as expected, including a newly generated version")
    val expected = AppInfo(
      app.toAppDefinition,
      maybeTasks = Some(immutable.Seq.empty),
      maybeCounts = Some(TaskCounts.zero),
      maybeDeployments = Some(immutable.Seq(Identifiable(plan.id)))
    )
    JsonTestHelper.assertThatJsonString(response.getEntity.asInstanceOf[String]).correspondsToJsonOf(expected)
  }

  test("Create a new app fails with Validation errors") {
    Given("An app with validation errors")
    val app = V2AppDefinition(id = PathId("/app"))
    val group = Group(PathId("/"), Set(app.toAppDefinition))
    val plan = DeploymentPlan(group, group)
    val body = Json.stringify(Json.toJson(app)).getBytes("UTF-8")
    groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)

    Then("A constraint violation exception is thrown")
    intercept[ConstraintViolationException] { appsResource.create(body, false, auth.request, auth.response) }
  }

  test("Create a new app with float instance count fails") {
    Given("The json of an invalid application")
    val invalidAppJson = Json.stringify(Json.obj("id" -> "/foo", "cmd" -> "cmd", "instances" -> 0.1))
    val group = Group(PathId("/"), Set.empty)
    val plan = DeploymentPlan(group, group)
    groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
    groupManager.rootGroup() returns Future.successful(group)

    Then("A constraint violation exception is thrown")
    val body = invalidAppJson.getBytes("UTF-8")
    intercept[RuntimeException] { appsResource.create(body, false, auth.request, auth.response) }
  }

  test("Replace an existing application") {
    Given("An app and group")
    val app = AppDefinition(id = PathId("/app"), cmd = Some("foo"))
    val group = Group(PathId("/"), Set(app))
    val plan = DeploymentPlan(group, group)
    val body = """{ "cmd": "bla" }""".getBytes("UTF-8")
    groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)

    When("The application is updates")
    val response = appsResource.replace(app.id.toString, body, false, auth.request, auth.response)

    Then("The application is updated")
    response.getStatus should be(200)
  }

  test("Restart an existing app") {
    val app = AppDefinition(id = PathId("/app"))
    val group = Group(PathId("/"), Set(app))
    val plan = DeploymentPlan(group, group)
    service.deploy(any, any) returns Future.successful(())

    groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
    val response = appsResource.restart(app.id.toString, force = true, auth.request, auth.response)
    response.getStatus should be(200)
  }

  test("Restart a non existing app will fail") {
    val missing = PathId("/app")
    groupManager.updateApp(any, any, any, any, any) returns Future.failed(new UnknownAppException(missing))
    intercept[UnknownAppException] { appsResource.restart(missing.toString, force = true, auth.request, auth.response) }
  }

  test("Index has counts and deployments by default (regression for #2171)") {
    Given("An app and group")
    val app = AppDefinition(id = PathId("/app"), cmd = Some("foo"))
    val expectedEmbeds: Set[Embed] = Set(Embed.Counts, Embed.Deployments)
    val appInfo = AppInfo(app, maybeDeployments = Some(Seq(Identifiable("deployment-123"))), maybeCounts = Some(TaskCounts(1, 2, 3, 4)))
    appInfoService.queryAll(any, eq(expectedEmbeds)) returns Future.successful(Seq(appInfo))

    When("The the index is fetched without any filters")
    val response = appsResource.index(null, null, null, new java.util.HashSet(), auth.request, auth.response)

    Then("The response holds counts and deployments")
    val appJson = Json.parse(response.getEntity.asInstanceOf[String])
    (appJson \ "apps" \\ "deployments" head) should be (Json.arr(Json.obj("id" -> "deployment-123")))
    (appJson \ "apps" \\ "tasksStaged" head) should be (JsNumber(1))
  }

  test("Search apps can be filtered") {
    val app1 = AppDefinition(id = PathId("/app/service-a"), cmd = Some("party hard"), labels = Map("a" -> "1", "b" -> "2"))
    val app2 = AppDefinition(id = PathId("/app/service-b"), cmd = Some("work hard"), labels = Map("a" -> "1", "b" -> "3"))
    val apps = Set(app1, app2)

    def search(cmd: Option[String], id: Option[String], label: Option[String]): Set[AppDefinition] = {
      val selector = appsResource.search(cmd, id, label)
      apps.filter(selector.matches)
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

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request
    val resp = auth.response
    val embed = new util.HashSet[String]()
    val app = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""
    groupManager.rootGroup() returns Future.successful(Group.empty)

    When(s"the resource is fetched from index")
    val index = appsResource.index("", "", "", embed, req, resp)
    Then("we receive a NotAuthenticated response")
    index.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the resource is fetched from create")
    val create = appsResource.create(app.getBytes("UTF-8"), false, req, resp)
    Then("we receive a NotAuthenticated response")
    create.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the resource is fetched from show")
    val show = appsResource.show("", embed, req, resp)
    Then("we receive a NotAuthenticated response")
    show.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the resource is fetched from replace")
    val replace = appsResource.replace("", app.getBytes("UTF-8"), false, req, resp)
    Then("we receive a NotAuthenticated response")
    replace.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the resource is fetched from replaceMulti")
    val replaceMultiple = appsResource.replaceMultiple(false, s"[$app]".getBytes("UTF-8"), req, resp)
    Then("we receive a NotAuthenticated response")
    replaceMultiple.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the resource is fetched from delete")
    val delete = appsResource.delete(false, "", req, resp)
    Then("we receive a NotAuthenticated response")
    delete.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the resource is fetched from restart")
    val restart = appsResource.restart("", false, req, resp)
    Then("we receive a NotAuthenticated response")
    restart.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    val resp = auth.response
    val embed = new util.HashSet[String]()
    val app = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""
    groupManager.rootGroup() returns Future.successful(Group.empty)

    When(s"the resource is fetched from create")
    val create = appsResource.create(app.getBytes("UTF-8"), false, req, resp)
    Then("we receive a NotAuthorized response")
    create.getStatus should be(auth.UnauthorizedStatus)

    When(s"the resource is fetched from show")
    val show = appsResource.show("", embed, req, resp)
    Then("we receive a NotAuthorized response")
    show.getStatus should be(auth.UnauthorizedStatus)

    When(s"the resource is fetched from replace")
    val replace = appsResource.replace("", app.getBytes("UTF-8"), false, req, resp)
    Then("we receive a NotAuthorized response")
    replace.getStatus should be(auth.UnauthorizedStatus)

    When(s"the resource is fetched from replaceMulti")
    val replaceMultiple = appsResource.replaceMultiple(false, s"[$app]".getBytes("UTF-8"), req, resp)
    Then("we receive a NotAuthorized response")
    replaceMultiple.getStatus should be(auth.UnauthorizedStatus)

    When(s"the resource is fetched from delete")
    val delete = appsResource.delete(false, "", req, resp)
    Then("we receive a NotAuthorized response")
    delete.getStatus should be(auth.UnauthorizedStatus)

    When(s"the resource is fetched from restart")
    val restart = appsResource.restart("", false, req, resp)
    Then("we receive a NotAuthorized response")
    restart.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access with limited authorization gives a filtered apps listing") {
    Given("An authorized identity with limited ACL's")
    auth.authFn = _.toString.startsWith("/visible")
    implicit val identity = auth.identity
    val selector = appsResource.selectAuthorized(AppSelector.forall(Seq.empty))
    val apps = Seq(
      AppDefinition("/visible/app".toPath),
      AppDefinition("/visible/other/foo/app".toPath),
      AppDefinition("/secure/app".toPath),
      AppDefinition("/root".toPath),
      AppDefinition("/other/great/app".toPath)
    )

    When("The selector selects applications")
    val filtered = apps.filter(selector.matches)

    Then("The list of filtered apps only contains apps according to ACL's")
    filtered should have size 2
    filtered.head should be (AppDefinition("/visible/app".toPath))
    filtered(1) should be (AppDefinition("/visible/other/foo/app".toPath))
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
  var auth: TestAuthFixture = _
  var appRepo: AppRepository = _
  var appTaskResource: AppTasksResource = _

  before {
    clock = ConstantClock()
    auth = new TestAuthFixture
    eventBus = mock[EventStream]
    service = mock[MarathonSchedulerService]
    taskTracker = mock[TaskTracker]
    taskKiller = mock[TaskKiller]
    healthCheckManager = mock[HealthCheckManager]
    taskFailureRepo = mock[TaskFailureRepository]
    config = mock[MarathonConf]
    appInfoService = mock[AppInfoService]
    groupManager = mock[GroupManager]
    appRepo = mock[AppRepository]
    appTaskResource = mock[AppTasksResource]
    appsResource = new AppsResource(
      clock,
      eventBus,
      appTaskResource,
      service,
      appInfoService,
      config,
      auth.auth,
      auth.auth,
      groupManager
    )
  }
}
