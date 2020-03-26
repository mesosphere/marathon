package mesosphere.marathon
package api.v2

import mesosphere.UnitTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.{LaunchQueue, LaunchStats}
import mesosphere.marathon.core.launchqueue.LaunchStats.QueuedInstanceInfoWithStatistics
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.raml.{App, Raml}
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import scala.jdk.CollectionConverters._
import mesosphere.marathon.test.{JerseyTest, MarathonTestHelper, SettableClock}
import mesosphere.mesos.NoOfferMatchReason
import org.mockito.Matchers
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class QueueResourceTest extends UnitTest with JerseyTest {
  case class Fixture(
      clock: SettableClock = new SettableClock(),
      config: MarathonConf = mock[MarathonConf],
      auth: TestAuthFixture = new TestAuthFixture,
      queue: LaunchQueue = mock[LaunchQueue],
      stats: LaunchStats = mock[LaunchStats],
      instanceTracker: InstanceTracker = mock[InstanceTracker],
      groupManager: GroupManager = mock[GroupManager]) {
    val queueResource: QueueResource = new QueueResource(
      clock,
      queue,
      instanceTracker,
      groupManager,
      auth.auth,
      auth.auth,
      config,
      stats
    )
  }
  implicit val appDefReader: Reads[AppDefinition] = Reads { js =>
    val ramlApp = js.as[App]
    val appDef: AppDefinition = Raml.fromRaml(ramlApp)
    // assume that any json we generate is canonical and valid
    JsSuccess(appDef)
  }

  "QueueResource" should {
    "return well formatted JSON" in new Fixture {
      //given
      val app = AppDefinition(id = "app".toAbsolutePath, acceptedResourceRoles = Set("*"), role = "*")
      val noMatch = OfferMatchResult.NoMatch(
        app,
        MarathonTestHelper.makeBasicOffer().build(),
        Seq(NoOfferMatchReason.InsufficientCpus, NoOfferMatchReason.DeclinedScarceResources),
        clock.now())
      stats.getStatistics() returns Future.successful(Seq(
        QueuedInstanceInfoWithStatistics(
          app, "*", inProgress = true, instancesLeftToLaunch = 23, finalInstanceCount = 23,
          backOffUntil = Some(clock.now() + 100.seconds), startedAt = clock.now(),
          rejectSummaryLastOffers = Map(NoOfferMatchReason.InsufficientCpus -> 1, NoOfferMatchReason.DeclinedScarceResources -> 2),
          rejectSummaryLaunchAttempt = Map(NoOfferMatchReason.InsufficientCpus -> 3, NoOfferMatchReason.DeclinedScarceResources -> 2),
          processedOffersCount = 3,
          unusedOffersCount = 1,
          lastMatch = None,
          lastNoMatch = None,
          lastNoMatches = Seq(noMatch)
        )
      ))

      //when
      val response = asyncRequest { r => queueResource.index(auth.request, Set("lastUnusedOffers").asJava, r) }

      //then
      response.getStatus should be(200)
      val json = Json.parse(response.getEntity.toString)
      val queuedApps = (json \ "queue").as[Seq[JsObject]]
      val jsonApp1 = queuedApps.find { apps => (apps \ "app" \ "id").as[String] == "/app" }.get

      (jsonApp1 \ "app").as[AppDefinition] should be(app)
      (jsonApp1 \ "count").as[Int] should be(23)
      (jsonApp1 \ "delay" \ "overdue").as[Boolean] should be(false)
      (jsonApp1 \ "delay" \ "timeLeftSeconds").as[Int] should be(100) //the deadline holds the current time...
      (jsonApp1 \ "processedOffersSummary" \ "processedOffersCount").as[Int] should be(3)
      (jsonApp1 \ "processedOffersSummary" \ "unusedOffersCount").as[Int] should be(1)
      (jsonApp1 \ "processedOffersSummary" \ "rejectSummaryLaunchAttempt" \ 4 \ "declined").as[Int] should be(3)
      (jsonApp1 \ "processedOffersSummary" \ "rejectSummaryLaunchAttempt" \ 9 \ "declined").as[Int] should be(2)
      val offer = (jsonApp1 \ "lastUnusedOffers").as[JsArray].value.head \ "offer"
      (offer \ "agentId").as[String] should be(noMatch.offer.getSlaveId.getValue)
      (offer \ "hostname").as[String] should be(noMatch.offer.getHostname)
      val resource = (offer \ "resources").as[JsArray].value.head
      (resource \ "name").as[String] should be("cpus")
      (resource \ "scalar").as[Int] should be(4)
      (resource \ "set") shouldBe a[JsUndefined]
      (resource \ "ranges") shouldBe a[JsUndefined]
    }

    "the generated info from the queue contains 0 if there is no delay" in new Fixture {
      //given
      val app = AppDefinition(id = "app".toAbsolutePath, role = "*")
      stats.getStatistics() returns Future.successful(Seq(
        QueuedInstanceInfoWithStatistics(
          app, "*", inProgress = true, instancesLeftToLaunch = 23, finalInstanceCount = 23,
          backOffUntil = Some(clock.now() - 100.seconds), startedAt = clock.now(), rejectSummaryLastOffers = Map.empty,
          rejectSummaryLaunchAttempt = Map.empty, processedOffersCount = 3, unusedOffersCount = 1, lastMatch = None,
          lastNoMatch = None, lastNoMatches = Seq.empty
        )
      ))
      //when
      val response = asyncRequest { r => queueResource.index(auth.request, Set.empty[String].asJava, r) }

      //then
      response.getStatus should be(200)
      val json = Json.parse(response.getEntity.toString)
      val queuedApps = (json \ "queue").as[Seq[JsObject]]
      val jsonApp1 = queuedApps.find { apps => (apps \ "app" \ "id").get == JsString("/app") }.get

      (jsonApp1 \ "app").as[AppDefinition] should be(app)
      (jsonApp1 \ "count").as[Int] should be(23)
      (jsonApp1 \ "delay" \ "overdue").as[Boolean] should be(true)
      (jsonApp1 \ "delay" \ "timeLeftSeconds").as[Int] should be(0)
    }

    "unknown application backoff can not be removed from the launch queue" in new Fixture {
      //given
      instanceTracker.specInstances(any, Matchers.eq(false))(any) returns Future.successful(Seq.empty)

      //when
      val response = asyncRequest { r => queueResource.resetDelay("unknown", auth.request, r) }

      //then
      response.getStatus should be(404)
    }

    "application backoff can be removed from the launch queue" in new Fixture {
      //given
      val app = AppDefinition(id = "app".toAbsolutePath, role = "*")
      val instances = Seq.fill(23)(Instance.scheduled(app))
      instanceTracker.specInstances(any, Matchers.eq(false))(any) returns Future.successful(instances)
      groupManager.runSpec(app.id) returns Some(app)

      //when
      val response = asyncRequest { r => queueResource.resetDelay("app", auth.request, r) }

      //then
      response.getStatus should be(204)
      verify(queue, times(1)).resetDelay(app)
    }

    "access without authentication is denied" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request

      When("the index is fetched")
      val index = asyncRequest { r => queueResource.index(req, Set.empty[String].asJava, r) }
      Then("we receive a NotAuthenticated response")
      index.getStatus should be(auth.NotAuthenticatedStatus)

      When("one delay is reset")
      val resetDelay = asyncRequest { r => queueResource.resetDelay("appId", req, r) }
      Then("we receive a NotAuthenticated response")
      resetDelay.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied if the app is in the queue" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      When("one delay is reset")
      val app = AppDefinition(id = "app".toAbsolutePath, role = "*")
      val instances = Seq.fill(23)(Instance.scheduled(app))
      instanceTracker.specInstances(any, Matchers.eq(false))(any) returns Future.successful(instances)
      groupManager.runSpec(app.id) returns Some(app)

      val resetDelay = asyncRequest { r => queueResource.resetDelay("app", req, r) }
      Then("we receive a not authorized response")
      resetDelay.getStatus should be(auth.UnauthorizedStatus)
    }

    "access without authorization leads to a 404 if the app is not in the queue" in new Fixture {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      When("one delay is reset")
      instanceTracker.specInstances(any, Matchers.eq(false))(any) returns Future.successful(Seq.empty)

      val resetDelay = asyncRequest { r => queueResource.resetDelay("appId", req, r) }
      Then("we receive a not authorized response")
      resetDelay.getStatus should be(404)
    }
  }
}
