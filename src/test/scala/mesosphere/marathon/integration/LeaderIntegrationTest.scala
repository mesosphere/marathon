package mesosphere.marathon.integration

import mesosphere.marathon.integration.setup.{ ProcessKeeper, IntegrationFunSuite, MarathonClusterIntegrationTest, WaitTestSupport }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.Try

class LeaderIntegrationTest extends IntegrationFunSuite
    with MarathonClusterIntegrationTest
    with GivenWhenThen
    with Matchers {

  test("all nodes return the same leader") {
    Given("a leader has been elected")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }

    When("calling /v2/leader on all nodes of a cluster")
    val results = marathonFacades.map(marathon => marathon.leader())

    Then("the requests should all be successful")
    results.foreach(_.code should be (200))

    And("they should all be the same")
    results.map(_.value).distinct should have length 1
  }

  test("all nodes return a redirect on GET /") {
    Given("a leader has been elected")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }

    When("get / on all nodes of a cluster")
    val results = marathonFacades.map(marathon => marathon.getPath("/"))

    Then("all nodes send a redirect")
    results.foreach(_.code should be (302))
  }

  test("the leader abdicates when it receives a DELETE") {
    Given("a leader")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }
    val leader = marathon.leader().value

    When("calling DELETE /v2/leader")
    val result = marathon.abdicate()

    Then("the request should be successful")
    result.code should be (200)
    (result.entityJson \ "message").as[String] should be ("Leadership abdicated")

    And("the leader must have changed")
    WaitTestSupport.waitUntil("the leader changes", 30.seconds) { marathon.leader().value != leader }
  }

  ignore("commit suicide if the zk connection is dropped") {
    // FIXME (gkleiman): investigate why this test fails (https://github.com/mesosphere/marathon/issues/3566)
    Given("a leader")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }

    When("ZooKeeper dies")
    ProcessKeeper.stopProcess("zookeeper")

    Then("Marathon commits suicide")
    val exitValueFuture = Future {
      ProcessKeeper.exitValue(s"marathon_${config.marathonBasePort}")
    }(scala.concurrent.ExecutionContext.global)

    Await.result(exitValueFuture, 30.seconds) should be > 0

    When("Zookeeper starts again")
    startZooKeeperProcess(wipeWorkDir = false)
    // Marathon is not running, but we want ProcessKeeper to notice that
    ProcessKeeper.stopProcess(s"marathon_${config.marathonBasePort}")
    startMarathon(config.marathonBasePort, marathonParameters: _*)

    Then("A new leader is elected")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) {
      Try(marathon.leader().code).getOrElse(500) == 200
    }
  }
}
