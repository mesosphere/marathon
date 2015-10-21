package mesosphere.marathon.integration

import mesosphere.marathon.integration.setup._
import org.scalatest.{ GivenWhenThen, Matchers }

class InfoIntegrationTest extends IntegrationFunSuite with SingleMarathonIntegrationTest with GivenWhenThen with Matchers {
  test("v2/info returns the right values") {
    When("fetching the info")
    val response = marathon.info

    Then("the response should be successful")
    response.code should be (200)

    val info = response.entityJson

    And("the http port should be correct")
    (info \ "http_config" \ "http_port").as[Int] should be (config.marathonBasePort)

    And("the ZooKeeper info should be correct")
    (info \ "zookeeper_config" \ "zk").as[String] should be (config.zk)

    And("the mesos master information should be correct")
    (info \ "marathon_config" \ "master").as[String] should be (config.master)

    And("the request should always be answered by the leader")
    (info \ "elected").as[Boolean] should be (true)

    And("the leader value in the JSON should match the one in the HTTP headers")
    val headerLeader =
      response.originalResponse.headers.find(_.name.equals("X-Marathon-Leader")).get.value.replace("http://", "")
    (info \ "leader").as[String] should be (headerLeader)

    And("the leader should match the value returned by /v2/leader")
    (info \ "leader").as[String] should be (marathon.leader().value.leader)
  }
}
