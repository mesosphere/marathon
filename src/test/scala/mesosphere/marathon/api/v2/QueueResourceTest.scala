package mesosphere.marathon.api.v2

import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskInfo
import mesosphere.marathon.state.{ Timestamp, AppDefinition }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class QueueResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  test("return well formatted JSON") {
    //given
    val app = AppDefinition(id = "app".toRootPath)
    queue.list returns Seq(
      QueuedTaskInfo(
        app, tasksLeftToLaunch = 23, taskLaunchesInFlight = 0, tasksLaunched = 0, clock.now() + 100.seconds
      )
    )

    //when
    val response = queueResource.index(auth.request)

    //then
    response.getStatus should be(200)
    val json = Json.parse(response.getEntity.asInstanceOf[String])
    val queuedApps = (json \ "queue").as[Seq[JsObject]]
    val jsonApp1 = queuedApps.find { apps => (apps \ "app" \ "id").as[String] == "/app" }.get

    (jsonApp1 \ "app").as[AppDefinition] should be(app)
    (jsonApp1 \ "count").as[Int] should be(23)
    (jsonApp1 \ "delay" \ "overdue").as[Boolean] should be(false)
    (jsonApp1 \ "delay" \ "timeLeftSeconds").as[Int] should be(100) //the deadline holds the current time...
  }

  test("the generated info from the queue contains 0 if there is no delay") {
    //given
    val app = AppDefinition(id = "app".toRootPath)
    queue.list returns Seq(
      QueuedTaskInfo(
        app, tasksLeftToLaunch = 23, taskLaunchesInFlight = 0, tasksLaunched = 0,
        backOffUntil = clock.now() - 100.seconds
      )
    )
    //when
    val response = queueResource.index(auth.request)

    //then
    response.getStatus should be(200)
    val json = Json.parse(response.getEntity.asInstanceOf[String])
    val queuedApps = (json \ "queue").as[Seq[JsObject]]
    val jsonApp1 = queuedApps.find { apps => (apps \ "app" \ "id").get == JsString("/app") }.get

    (jsonApp1 \ "app").as[AppDefinition] should be(app)
    (jsonApp1 \ "count").as[Int] should be(23)
    (jsonApp1 \ "delay" \ "overdue").as[Boolean] should be(true)
    (jsonApp1 \ "delay" \ "timeLeftSeconds").as[Int] should be(0)
  }

  test("unknown application backoff can not be removed from the launch queue") {
    //given
    queue.list returns Seq.empty

    //when
    val response = queueResource.resetDelay("unknown", auth.request)

    //then
    response.getStatus should be(404)
  }

  test("application backoff can be removed from the launch queue") {
    //given
    val app = AppDefinition(id = "app".toRootPath)
    queue.list returns Seq(
      QueuedTaskInfo(
        app, tasksLeftToLaunch = 23, taskLaunchesInFlight = 0, tasksLaunched = 0,
        backOffUntil = clock.now() + 100.seconds
      )
    )

    //when
    val response = queueResource.resetDelay("app", auth.request)

    //then
    response.getStatus should be(204)
    verify(queue, times(1)).resetDelay(app)
  }

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request

    When(s"the index is fetched")
    val index = queueResource.index(req)
    Then("we receive a NotAuthenticated response")
    index.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"one delay is reset")
    val resetDelay = queueResource.resetDelay("appId", req)
    Then("we receive a NotAuthenticated response")
    resetDelay.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied if the app is in the queue") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    When(s"one delay is reset")
    val appId = "appId".toRootPath
    val taskCount = LaunchQueue.QueuedTaskInfo(AppDefinition(appId), 0, 0, 0, Timestamp.now())
    queue.list returns Seq(taskCount)

    val resetDelay = queueResource.resetDelay("appId", req)
    Then("we receive a not authorized response")
    resetDelay.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access without authorization leads to a 404 if the app is not in the queue") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    When(s"one delay is reset")
    queue.list returns Seq.empty

    val resetDelay = queueResource.resetDelay("appId", req)
    Then("we receive a not authorized response")
    resetDelay.getStatus should be(404)
  }

  var clock: Clock = _
  var config: MarathonConf = _
  var queueResource: QueueResource = _
  var auth: TestAuthFixture = _
  var queue: LaunchQueue = _

  before {
    clock = ConstantClock()
    auth = new TestAuthFixture
    config = mock[MarathonConf]
    queue = mock[LaunchQueue]
    queueResource = new QueueResource(
      clock,
      queue,
      auth.auth,
      auth.auth,
      config
    )
  }
}
