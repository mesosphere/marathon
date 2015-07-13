package mesosphere.marathon.integration

import mesosphere.marathon.integration.setup._
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.Json

class InfoIntegrationTest extends IntegrationFunSuite with SingleMarathonIntegrationTest with GivenWhenThen with Matchers {
  test("v2/info returns the right values") {
    When("fetching the info")
    val response = marathon.info

    Then("the response should be successful")
    response.code should be (200)

    val info = response.entityJson

    And("the http port should be correct")
    info \ "http_config" \ "http_port" should be (Json.toJson(config.marathonBasePort))

    And("the ZooKeeper info should be correct")
    info \ "zookeeper_config" \ "zk" should be (Json.toJson(config.zk))

    And("the mesos master information should be correct")
    info \ "marathon_config" \ "master" should be (Json.toJson(config.master))

    And("the request should always be answered by the leader")
    info \ "elected" should be (Json.toJson(true))

    And("the leader value in the JSON should match the one in the HTTP headers")
    val headerLeader =
      response.originalResponse.headers.find(_.name.equals("X-Marathon-Leader")).get.value.replace("http://", "")
    info \ "leader" should be (Json.toJson(headerLeader))

    And("the leader should match the value returned by /v2/leader")
    info \ "leader" should be (Json.toJson(marathon.leader.value.leader))
  }
}
