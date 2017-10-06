package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.model.{StatusCodes, Uri}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.api.{JsonTestHelper, TestAuthFixture}
import mesosphere.marathon.api.akkahttp.AuthDirectives.NotAuthenticated
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfoWithStatistics
import mesosphere.marathon.raml.{App, Raml}
import mesosphere.marathon.state.{AppDefinition, VersionInfo}
import mesosphere.marathon.test.{MarathonTestHelper, SettableClock}
import mesosphere.mesos.NoOfferMatchReason
import org.scalatest.Inside
import mesosphere.marathon.state.PathId._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.collection.immutable.Seq
import scala.concurrent.Future

class QueueControllerTest extends UnitTest with ScalatestRouteTest with Inside with StrictLogging {

  implicit val appDefReader: Reads[AppDefinition] = Reads { js =>
    val ramlApp = js.as[App]
    val appDef: AppDefinition = Raml.fromRaml(ramlApp)
    // assume that any json we generate is canonical and valid
    JsSuccess(appDef)
  }

  "QueueController" should {
    "deny access withouth authentication" in {
      Given("an unauthenticated request")
      val f = new Fixture(authenticated = false)

      When("we try to fetch the queue")
      Get(Uri./) ~> f.controller.route ~> check {
        Then("we receive a NotAuthenticated response")
        rejection shouldBe a[NotAuthenticated]
        inside(rejection) {
          case NotAuthenticated(response) =>
            response.status should be(StatusCodes.Forbidden)
        }
      }
    }

    "return an empty json array if nothing in the queue" in {
      Given("an authenticated request")
      val f = new Fixture(authenticated = true)

      When("we try to fetch the queue")
      Get(Uri./) ~> f.controller.route ~> check {
        Then("we receive an empty json array")
        status should be(StatusCodes.OK)

        val expected =
          """{
            |  "queue" : [ ]
            |}""".stripMargin
        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }

    "return a well formatted json response if there is an app in the queue" in {
      Given("an authenticated request")
      val f = new Fixture(authenticated = true)

      And("an existing app in the queue")
      val app = AppDefinition(id = "app".toRootPath, acceptedResourceRoles = Set("*"), versionInfo = VersionInfo.forNewConfig(f.clock.now()))
      val noMatch = OfferMatchResult.NoMatch(app, MarathonTestHelper.makeBasicOffer().build(), Seq(NoOfferMatchReason.InsufficientCpus), f.clock.now())
      f.launchQueue.listWithStatisticsAsync returns Future.successful(Seq(
        QueuedInstanceInfoWithStatistics(
          app, inProgress = true, instancesLeftToLaunch = 23, finalInstanceCount = 23,
          backOffUntil = f.clock.now() + 100.seconds, startedAt = f.clock.now(),
          rejectSummaryLastOffers = Map(NoOfferMatchReason.InsufficientCpus -> 1),
          rejectSummaryLaunchAttempt = Map(NoOfferMatchReason.InsufficientCpus -> 3), processedOffersCount = 3, unusedOffersCount = 1,
          lastMatch = None, lastNoMatch = None, lastNoMatches = Seq(noMatch)
        )
      ))

      When("we try to fetch the queue")
      Get(Uri./) ~> f.controller.route ~> check {
        Then("we receive an empty json array")
        status should be(StatusCodes.OK)

        val expected =
          """{
            |  "queue" : [ {
            |    "count" : 23,
            |    "delay" : {
            |      "timeLeftSeconds" : 100,
            |      "overdue" : false
            |    },
            |    "since" : "2015-04-09T12:30:00Z",
            |    "processedOffersSummary" : {
            |      "processedOffersCount" : 3,
            |      "unusedOffersCount" : 1,
            |      "rejectSummaryLastOffers" : [ {
            |        "reason" : "UnfulfilledRole",
            |        "declined" : 0,
            |        "processed" : 1
            |      }, {
            |        "reason" : "UnfulfilledConstraint",
            |        "declined" : 0,
            |        "processed" : 1
            |      }, {
            |        "reason" : "NoCorrespondingReservationFound",
            |        "declined" : 0,
            |        "processed" : 1
            |      }, {
            |        "reason" : "InsufficientCpus",
            |        "declined" : 1,
            |        "processed" : 1
            |      }, {
            |        "reason" : "InsufficientMemory",
            |        "declined" : 0,
            |        "processed" : 0
            |      }, {
            |        "reason" : "InsufficientDisk",
            |        "declined" : 0,
            |        "processed" : 0
            |      }, {
            |        "reason" : "InsufficientGpus",
            |        "declined" : 0,
            |        "processed" : 0
            |      }, {
            |        "reason" : "InsufficientPorts",
            |        "declined" : 0,
            |        "processed" : 0
            |      } ],
            |      "rejectSummaryLaunchAttempt" : [ {
            |        "reason" : "UnfulfilledRole",
            |        "declined" : 0,
            |        "processed" : 3
            |      }, {
            |        "reason" : "UnfulfilledConstraint",
            |        "declined" : 0,
            |        "processed" : 3
            |      }, {
            |        "reason" : "NoCorrespondingReservationFound",
            |        "declined" : 0,
            |        "processed" : 3
            |      }, {
            |        "reason" : "InsufficientCpus",
            |        "declined" : 3,
            |        "processed" : 3
            |      }, {
            |        "reason" : "InsufficientMemory",
            |        "declined" : 0,
            |        "processed" : 0
            |      }, {
            |        "reason" : "InsufficientDisk",
            |        "declined" : 0,
            |        "processed" : 0
            |      }, {
            |        "reason" : "InsufficientGpus",
            |        "declined" : 0,
            |        "processed" : 0
            |      }, {
            |        "reason" : "InsufficientPorts",
            |        "declined" : 0,
            |        "processed" : 0
            |      } ]
            |    },
            |    "app" : {
            |      "id" : "/app",
            |      "acceptedResourceRoles" : [ "*" ],
            |      "backoffFactor" : 1.15,
            |      "backoffSeconds" : 1,
            |      "cpus" : 1,
            |      "disk" : 0,
            |      "executor" : "",
            |      "instances" : 1,
            |      "labels" : { },
            |      "maxLaunchDelaySeconds" : 3600,
            |      "mem" : 128,
            |      "gpus" : 0,
            |      "networks" : [ {
            |        "mode" : "host"
            |      } ],
            |      "portDefinitions" : [ ],
            |      "requirePorts" : false,
            |      "upgradeStrategy" : {
            |        "maximumOverCapacity" : 1,
            |        "minimumHealthCapacity" : 1
            |      },
            |      "version" : "2015-04-09T12:30:00Z",
            |      "versionInfo" : {
            |        "lastScalingAt" : "2015-04-09T12:30:00Z",
            |        "lastConfigChangeAt" : "2015-04-09T12:30:00Z"
            |      },
            |      "killSelection" : "YOUNGEST_FIRST",
            |      "unreachableStrategy" : {
            |        "inactiveAfterSeconds" : 0,
            |        "expungeAfterSeconds" : 0
            |      }
            |    }
            |  } ]
            |}""".stripMargin

        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }
  }

  class Fixture(authenticated: Boolean = true, authorized: Boolean = true) {

    val clock: SettableClock = new SettableClock()
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    launchQueue.listWithStatisticsAsync returns Future.successful(Seq.empty)

    val authFixture = new TestAuthFixture()
    authFixture.authenticated = authenticated
    authFixture.authorized = authorized

    implicit val electionService = mock[ElectionService]
    electionService.isLeader returns true

    implicit val authenticator = authFixture.auth

    val controller = new QueueController(clock, launchQueue)
  }
}
