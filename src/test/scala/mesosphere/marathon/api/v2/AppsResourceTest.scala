package mesosphere.marathon.api.v2

import akka.event.EventStream
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, MarathonSpec }
import org.mockito.Matchers.{ any, eq => meq, same }
import org.mockito.Mockito.{ verify, when }
import org.scalatest.Matchers

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
}
