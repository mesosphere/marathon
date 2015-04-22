package mesosphere.marathon.api.v2

import akka.event.EventStream
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, MarathonSpec }
import org.mockito.Matchers.{ any, eq => meq, same }
import org.mockito.Mockito.{ verify, when }
import org.scalatest.Matchers
import PathId._

import scala.concurrent.Future
import scala.concurrent.duration._

class AppsResourceTest extends MarathonSpec with Matchers {

  var eventBus: EventStream = _
  var service: MarathonSchedulerService = _
  var taskTracker: TaskTracker = _
  var healthCheckManager: HealthCheckManager = _
  var taskFailureRepo: TaskFailureRepository = _
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var appsResource: AppsResource = _

  before {
    eventBus = mock[EventStream]
    service = mock[MarathonSchedulerService]
    taskTracker = mock[TaskTracker]
    healthCheckManager = mock[HealthCheckManager]
    taskFailureRepo = mock[TaskFailureRepository]
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]
    appsResource = new AppsResource(
      eventBus,
      service,
      taskTracker,
      healthCheckManager,
      taskFailureRepo,
      config,
      groupManager
    )
  }

  // regression test for #1228
  test("Restart app uses force parameter") {
    val app = AppDefinition(id = PathId("/app"))
    val group = mock[Group]
    val newGroup = Group(PathId("/"), Set(app))

    when(config.zkTimeoutDuration).thenReturn(5.seconds)
    when(group.updateApp(same(app.id), any(), any())).thenReturn(newGroup)
    when(service.getApp(app.id)).thenReturn(Some(app))
    when(service.deploy(any(), any())).thenReturn(Future.successful(()))
    when(groupManager.group(app.id.parent)).thenReturn(Future.successful(Some(group)))

    appsResource.restart(app.id.toString, force = true)

    verify(service).deploy(any(), meq(true))
  }

  test("Search apps can be filtered") {
    val app1 = AppDefinition(id = "/app/service-a".toRootPath, cmd = Some("party hard"), labels = Map("a" -> "1", "b" -> "2"))
    val app2 = AppDefinition(id = "/app/service-b".toRootPath, cmd = Some("work hard"), labels = Map("a" -> "1", "b" -> "3"))
    when(service.listApps()).thenReturn(Seq(app1, app2))

    appsResource.search(None, None, None) should be(Seq(app1, app2))
    appsResource.search(Some(""), None, None) should be(Seq(app1, app2))
    appsResource.search(Some("party"), None, None) should be(Seq(app1))
    appsResource.search(Some("work"), None, None) should be(Seq(app2))
    appsResource.search(Some("hard"), None, None) should be(Seq(app1, app2))
    appsResource.search(Some("none"), None, None) should be(Seq.empty)

    appsResource.search(None, Some("app"), None) should be(Seq(app1, app2))
    appsResource.search(None, Some("service-a"), None) should be(Seq(app1))
    appsResource.search(Some("party"), Some("app"), None) should be(Seq(app1))
    appsResource.search(Some("work"), Some("app"), None) should be(Seq(app2))
    appsResource.search(Some("hard"), Some("service-a"), None) should be(Seq(app1))
    appsResource.search(Some(""), Some(""), None) should be(Seq(app1, app2))

    appsResource.search(None, None, Some("b==2")) should be(Seq(app1))
    appsResource.search(Some("party"), Some("app"), Some("a==1")) should be(Seq(app1))
    appsResource.search(Some("work"), Some("app"), Some("a==1")) should be(Seq(app2))
    appsResource.search(Some("hard"), Some("service-a"), Some("a==1")) should be(Seq(app1))
    appsResource.search(Some(""), Some(""), Some("")) should be(Seq(app1, app2))
  }

}
