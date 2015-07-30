package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskCount
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import mesosphere.util.Mockito
import org.scalatest.Matchers
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class QueueResourceTest extends MarathonSpec with Matchers with Mockito {

  test("return well formatted JSON") {
    //given
    val queue = mock[LaunchQueue]
    val app = AppDefinition(id = "app".toRootPath)
    val clock: ConstantClock = ConstantClock()
    val resource = new QueueResource(clock, queue, mock[MarathonConf])
    queue.list returns Seq(
      QueuedTaskCount(
        app, tasksLeftToLaunch = 23, taskLaunchesInFlight = 0, tasksLaunchedOrRunning = 0, clock.now() + 100.seconds
      )
    )

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
    (jsonApp1 \ "delay" \ "timeLeftSeconds").as[Int] should be(100) //the deadline holds the current time...
  }

  test("the generated info from the queue contains 0 if there is no delay") {
    //given
    val queue = mock[LaunchQueue]
    val app = AppDefinition(id = "app".toRootPath)
    val clock: ConstantClock = ConstantClock()
    val resource = new QueueResource(clock, queue, mock[MarathonConf])
    queue.list returns Seq(
      QueuedTaskCount(
        app, tasksLeftToLaunch = 23, taskLaunchesInFlight = 0, tasksLaunchedOrRunning = 0,
        backOffUntil = clock.now() - 100.seconds
      )
    )
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
    val queue = mock[LaunchQueue]
    val resource = new QueueResource(ConstantClock(), queue, mock[MarathonConf])
    queue.list returns Seq.empty

    //when
    val response = resource.resetDelay("unknown")

    //then
    response.getStatus should be(404)
  }

  test("application backoff can be removed from the taskqueue") {
    //given
    val queue = mock[LaunchQueue]
    val app = AppDefinition(id = "app".toRootPath)
    val clock: ConstantClock = ConstantClock()
    val resource = new QueueResource(clock, queue, mock[MarathonConf])
    queue.list returns Seq(
      QueuedTaskCount(
        app, tasksLeftToLaunch = 23, taskLaunchesInFlight = 0, tasksLaunchedOrRunning = 0,
        backOffUntil = clock.now() + 100.seconds
      )
    )

    //when
    val response = resource.resetDelay("app")

    //then
    response.getStatus should be(204)
    verify(queue, times(1)).resetDelay(app)
  }
}
