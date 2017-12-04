package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.api.akkahttp.AuthDirectives.NotAuthorized
import mesosphere.marathon.api.{ JsonTestHelper, TestAuthFixture }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.launchqueue.LaunchQueue.{ QueuedInstanceInfo, QueuedInstanceInfoWithStatistics }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, VersionInfo }
import mesosphere.marathon.test.{ MarathonTestHelper, SettableClock }
import mesosphere.mesos.NoOfferMatchReason
import org.scalatest.Inside

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class QueueControllerTest extends UnitTest with ScalatestRouteTest with Inside with RouteBehaviours with StrictLogging {

  "QueueController" should {

    // Unauthenticated access test cases
    {
      val controller = Fixture(authenticated = false).controller
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get(Uri./))
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Delete("/unknown/delay"))
    }

    // Entity not found test cases
    {
      val f = new Fixture(authenticated = true)
      behave like unknownEntity(forRoute = f.controller.route, withRequest = Delete("/unknown/delay"), withMessage = "Application /unknown not found in tasks queue.")
    }
    {
      Given("an authenticated but not authorized request")
      val f = new Fixture(authenticated = true, authorized = false)
      behave like unknownEntity(forRoute = f.controller.route, withRequest = Delete("/another-unknown/delay"), withMessage = "Application /another-unknown not found in tasks queue.")
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
      f.launchQueue.listWithStatisticsAsync returns Future.successful(queueInfoWithStatistics(f))

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

    "return a well formatted json with lastUnusedOffers response if there is an app in the queue" in {
      Given("an authenticated request")
      val f = new Fixture(authenticated = true)

      And("an existing app in the queue")
      f.launchQueue.listWithStatisticsAsync returns Future.successful(queueInfoWithStatistics(f))

      When("we try to fetch the queue")
      Get("/?embed=lastUnusedOffers") ~> f.controller.route ~> check {
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
            |    "lastUnusedOffers" : [ {
            |      "offer" : {
            |        "id" : "1",
            |        "hostname" : "localhost",
            |        "agentId" : "slave0",
            |        "resources" : [ {
            |          "name" : "cpus",
            |          "role" : "*",
            |          "scalar" : 4
            |        }, {
            |          "name" : "gpus",
            |          "role" : "*",
            |          "scalar" : 0
            |        }, {
            |          "name" : "mem",
            |          "role" : "*",
            |          "scalar" : 16000
            |        }, {
            |          "name" : "disk",
            |          "role" : "*",
            |          "scalar" : 1
            |        }, {
            |          "name" : "ports",
            |          "role" : "*",
            |          "ranges" : [ {
            |            "begin" : 31000,
            |            "end" : 32000
            |          } ]
            |        } ],
            |        "attributes" : [ ]
            |      },
            |      "reason" : [ "InsufficientCpus" ],
            |      "timestamp" : "2015-04-09T12:30:00Z"
            |    } ],
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

    "application's backoff delay can be reset" in {
      Given("an authenticated request")
      val f = new Fixture(authenticated = true)

      And("there is /app in the queue")
      val app = AppDefinition(id = "app".toRootPath)
      f.launchQueue.listAsync returns Future.successful(Seq(
        QueuedInstanceInfo(
          app, inProgress = true, instancesLeftToLaunch = 23, finalInstanceCount = 23,
          backOffUntil = f.clock.now() + 100.seconds, startedAt = f.clock.now()
        )
      ))

      When("we try to reset delay")
      Delete("/app/delay") ~> f.controller.route ~> check {
        Then("we succeed")
        status shouldBe StatusCodes.NoContent
      }
    }

    "access without authorization is denied if the app is in the queue" in {
      Given("an authenticated but not authorized request")
      val f = new Fixture(authenticated = true, authorized = false)

      And("an app in the queue")
      f.launchQueue.listAsync returns Future.successful(Seq(
        LaunchQueue.QueuedInstanceInfo(AppDefinition("app".toRootPath), inProgress = false, 0, 0,
          backOffUntil = f.clock.now() + 100.seconds, startedAt = f.clock.now())))

      Delete("/app/delay") ~> f.controller.route ~> check {
        Then("the request fails with NotAuthorized")
        rejection shouldBe a[NotAuthorized]
        inside(rejection) {
          case NotAuthorized(response) =>
            response.status should be(StatusCodes.Unauthorized)
        }
      }
    }
  }

  def queueInfoWithStatistics(f: Fixture): Seq[LaunchQueue.QueuedInstanceInfoWithStatistics] = {
    val app = AppDefinition(id = "app".toRootPath, acceptedResourceRoles = Set("*"), versionInfo = VersionInfo.forNewConfig(f.clock.now()))
    val noMatch = OfferMatchResult.NoMatch(app, MarathonTestHelper.makeBasicOffer().build(), Seq(NoOfferMatchReason.InsufficientCpus), f.clock.now())
    Seq(
      QueuedInstanceInfoWithStatistics(
        app, inProgress = true, instancesLeftToLaunch = 23, finalInstanceCount = 23,
        backOffUntil = f.clock.now() + 100.seconds, startedAt = f.clock.now(),
        rejectSummaryLastOffers = Map(NoOfferMatchReason.InsufficientCpus -> 1),
        rejectSummaryLaunchAttempt = Map(NoOfferMatchReason.InsufficientCpus -> 3), processedOffersCount = 3, unusedOffersCount = 1,
        lastMatch = None, lastNoMatch = None, lastNoMatches = Seq(noMatch)
      )
    )
  }

  case class Fixture(authenticated: Boolean = true, authorized: Boolean = true) {

    val clock: SettableClock = new SettableClock()
    val launchQueue: LaunchQueue = mock[LaunchQueue]
    launchQueue.listWithStatisticsAsync returns Future.successful(Seq.empty)
    launchQueue.listAsync returns Future.successful(Seq.empty)

    val authFixture = new TestAuthFixture()
    authFixture.authenticated = authenticated
    authFixture.authorized = authorized

    val electionService = mock[ElectionService]
    electionService.isLeader returns true

    implicit val authenticator = authFixture.auth

    val controller = new QueueController(clock, launchQueue, electionService)
  }
}
