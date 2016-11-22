package mesosphere.marathon.api.v2

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.stream._
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.launchqueue.LaunchQueue.{ QueuedInstanceInfo, QueuedInstanceInfoWithStatistics }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper, Mockito }
import mesosphere.mesos.NoOfferMatchReason
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class QueueResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  test("return well formatted JSON") {
    //given
    val app = AppDefinition(id = "app".toRootPath, acceptedResourceRoles = Set("*"))
    val noMatch = OfferMatchResult.NoMatch(app, MarathonTestHelper.makeBasicOffer().build(), Seq(NoOfferMatchReason.InsufficientCpus), clock.now())
    queue.listWithStatistics returns Seq(
      QueuedInstanceInfoWithStatistics(
        app, inProgress = true, instancesLeftToLaunch = 23, finalInstanceCount = 23, unreachableInstances = 0,
        backOffUntil = clock.now() + 100.seconds, startedAt = clock.now(),
        rejectSummaryLastOffers = Map(NoOfferMatchReason.InsufficientCpus -> 1),
        rejectSummaryLaunchAttempt = Map(NoOfferMatchReason.InsufficientCpus -> 3), processedOffersCount = 3, unusedOffersCount = 1,
        lastMatch = None, lastNoMatch = None, lastNoMatches = Seq(noMatch)
      )
    )

    //when
    val response = queueResource.index(auth.request, Set("lastUnusedOffers"))

    //then
    response.getStatus should be(200)
    val json = Json.parse(response.getEntity.asInstanceOf[String])
    val queuedApps = (json \ "queue").as[Seq[JsObject]]
    val jsonApp1 = queuedApps.find { apps => (apps \ "app" \ "id").as[String] == "/app" }.get

    (jsonApp1 \ "app").as[AppDefinition] should be(app)
    (jsonApp1 \ "count").as[Int] should be(23)
    (jsonApp1 \ "delay" \ "overdue").as[Boolean] should be(false)
    (jsonApp1 \ "delay" \ "timeLeftSeconds").as[Int] should be(100) //the deadline holds the current time...
    (jsonApp1 \ "processedOffersSummary" \ "processedOffersCount").as[Int] should be(3)
    (jsonApp1 \ "processedOffersSummary" \ "unusedOffersCount").as[Int] should be(1)
    (jsonApp1 \ "processedOffersSummary" \ "rejectSummaryLaunchAttempt" \ 3 \ "declined").as[Int] should be(3)
    val offer = (jsonApp1 \ "lastUnusedOffers").as[JsArray].value.head \ "offer"
    (offer \ "agentId").as[String] should be(noMatch.offer.getSlaveId.getValue)
    (offer \ "hostname").as[String] should be(noMatch.offer.getHostname)
    val resource = (offer \ "resources").as[JsArray].value.head
    (resource \ "name").as[String] should be("cpus")
    (resource \ "scalar").as[Int] should be(4)
    (resource \ "set") shouldBe a[JsUndefined]
    (resource \ "ranges") shouldBe a[JsUndefined]
  }

  test("the generated info from the queue contains 0 if there is no delay") {
    //given
    val app = AppDefinition(id = "app".toRootPath)
    queue.listWithStatistics returns Seq(
      QueuedInstanceInfoWithStatistics(
        app, inProgress = true, instancesLeftToLaunch = 23, finalInstanceCount = 23, unreachableInstances = 0,
        backOffUntil = clock.now() - 100.seconds, startedAt = clock.now(), rejectSummaryLastOffers = Map.empty,
        rejectSummaryLaunchAttempt = Map.empty, processedOffersCount = 3, unusedOffersCount = 1, lastMatch = None,
        lastNoMatch = None, lastNoMatches = Seq.empty
      )
    )
    //when
    val response = queueResource.index(auth.request, new java.util.HashSet())

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
      QueuedInstanceInfo(
        app, inProgress = true, instancesLeftToLaunch = 23, finalInstanceCount = 23, unreachableInstances = 0,
        backOffUntil = clock.now() + 100.seconds, startedAt = clock.now()
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

    When("the index is fetched")
    val index = queueResource.index(req, new java.util.HashSet())
    Then("we receive a NotAuthenticated response")
    index.getStatus should be(auth.NotAuthenticatedStatus)

    When("one delay is reset")
    val resetDelay = queueResource.resetDelay("appId", req)
    Then("we receive a NotAuthenticated response")
    resetDelay.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied if the app is in the queue") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    When("one delay is reset")
    val appId = "appId".toRootPath
    val taskCount = LaunchQueue.QueuedInstanceInfo(AppDefinition(appId), inProgress = false, 0, 0, unreachableInstances = 0,
      backOffUntil = clock.now() + 100.seconds, startedAt = clock.now())
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

    When("one delay is reset")
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
