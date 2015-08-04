package mesosphere.marathon.api.v2

import java.util.concurrent.atomic.AtomicInteger

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskQueue.QueuedTask
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import mesosphere.marathon.tasks.TaskQueue
import mesosphere.marathon.tasks.{ OfferReviver, TaskQueue }
import mesosphere.util.Mockito
import org.scalatest.Matchers
import play.api.libs.json._
import scala.concurrent.duration._
import scala.collection.immutable.Seq

class QueueResourceTest extends MarathonSpec with Matchers with Mockito {

  test("return well formatted JSON") {
    //given
    val queue = mock[TaskQueue]
    val app = AppDefinition(id = "app".toRootPath)
    val resource = new QueueResource(queue, mock[MarathonConf])
    queue.listWithDelay returns Seq(QueuedTask(app, new AtomicInteger(23)) -> 100.seconds.fromNow)

    //when
    val response = resource.index()

    //then
    response.getStatus should be(200)
    val json = Json.parse(response.getEntity.asInstanceOf[String])
    val queuedApps = (json \ "queue").as[Seq[JsObject]]
    val jsonApp1 = queuedApps.find(_ \ "app" \ "id" == JsString("/app")).get

    jsonApp1 \ "app" should be(Json.toJson(V2AppDefinition(app)))
    jsonApp1 \ "count" should be(Json.toJson(23))
    jsonApp1 \ "delay" \ "overdue" should be(Json.toJson(false))
    (jsonApp1 \ "delay" \ "timeLeftSeconds").as[Int] should be(100 +- 5) //the deadline holds the current time...
  }

  test("the generated info from the queue contains 0 if there is no delay") {
    //given
    val queue = mock[TaskQueue]
    val app = AppDefinition(id = "app".toRootPath)
    val resource = new QueueResource(queue, mock[MarathonConf])
    queue.listWithDelay returns Seq(QueuedTask(app, new AtomicInteger(23)) -> -100.seconds.fromNow)

    //when
    val response = resource.index()

    //then
    response.getStatus should be(200)
    val json = Json.parse(response.getEntity.asInstanceOf[String])
    val queuedApps = (json \ "queue").as[Seq[JsObject]]
    val jsonApp1 = queuedApps.find(_ \ "app" \ "id" == JsString("/app")).get

    jsonApp1 \ "app" should be(Json.toJson(V2AppDefinition(app)))
    jsonApp1 \ "count" should be(Json.toJson(23))
    jsonApp1 \ "delay" \ "overdue" should be(Json.toJson(true))
    jsonApp1 \ "delay" \ "timeLeftSeconds" should be(Json.toJson(0))
  }

  test("unknown application backoff can not be removed from the taskqueue") {
    //given
    val queue = mock[TaskQueue]
    val resource = new QueueResource(queue, mock[MarathonConf])
    queue.listWithDelay returns Seq.empty

    //when
    val response = resource.resetDelay("unknown")

    //then
    response.getStatus should be(404)
  }

  test("application backoff can be removed from the taskqueue") {
    //given
    val queue = mock[TaskQueue]
    val app = AppDefinition(id = "app".toRootPath)
    val resource = new QueueResource(queue, mock[MarathonConf])
    queue.listWithDelay returns Seq(QueuedTask(app, new AtomicInteger(23)) -> 100.seconds.fromNow)

    //when
    val response = resource.resetDelay("app")

    //then
    response.getStatus should be(204)
    verify(queue, times(1)).resetDelay(app)
  }
}
