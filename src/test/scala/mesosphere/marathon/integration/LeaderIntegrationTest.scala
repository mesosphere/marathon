package mesosphere.marathon.integration

import mesosphere.marathon.integration.setup.{ MarathonClusterIntegrationTest, IntegrationFunSuite, WaitTestSupport }
import play.api.libs.json.Json
import scala.concurrent.duration._
import org.scalatest.{ GivenWhenThen, Matchers }

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
    (result.entityJson \ "message") should be (Json.toJson("Leadership abdicted"))

    And("the leader must have changed")
    WaitTestSupport.waitUntil("the leader changes", 30.seconds) { marathon.leader().value != leader }
  }
}
