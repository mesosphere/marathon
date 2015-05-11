package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import mesosphere.marathon.tasks.TaskQueue
import org.scalatest.Matchers
import play.api.libs.json.{ JsObject, Json }

class QueueResourceTest extends MarathonSpec with Matchers {

  // regression test for #1210
  test("return well formatted JSON") {
    val queue = new TaskQueue
    val app1 = AppDefinition(id = "app1".toRootPath)
    val app2 = AppDefinition(id = "app2".toRootPath)
    val resource = new QueueResource(queue, mock[MarathonConf])

    queue.add(app1, 4)
    queue.add(app2, 2)

    for (_ <- 0 until 10)
      queue.rateLimiter.addDelay(app2)

    val json = Json.parse(resource.index().getEntity.toString)

    val queuedApp1 = Json.obj(
      "count" -> 4,
      "delay" -> Json.obj(
        "overdue" -> true
      ),
      "app" -> V2AppDefinition(app1)
    )

    val queuedApp2 = Json.obj(
      "count" -> 2,
      "delay" -> Json.obj(
        "overdue" -> false
      ),
      "app" -> V2AppDefinition(app2)
    )

    val queuedApps = (json \ "queue").as[Seq[JsObject]]

    assert(queuedApps.contains(queuedApp1))
    assert(queuedApps.contains(queuedApp2))
  }
}
