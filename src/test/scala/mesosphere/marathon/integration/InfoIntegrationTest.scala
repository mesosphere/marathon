package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._

@SerialIntegrationTest
@IntegrationTest
class InfoIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {
  "Info" should {
    "v2/info returns the right values" in {
      When("fetching the info")
      val response = marathon.info

      Then("the response should be successful")
      response.code should be(200)

      val info = response.entityJson

      And("the http port should be correct")
      (info \ "http_config" \ "http_port").as[Int] should be(marathonServer.httpPort)

      And("the ZooKeeper info should be correct")
      (info \ "zookeeper_config" \ "zk").as[String] should be(marathonServer.config("zk"))

      And("the mesos master information should be correct")
      (info \ "marathon_config" \ "master").as[String] should be(marathonServer.config("master"))

      And("the request should always be answered by the leader")
      (info \ "elected").as[Boolean] should be(true)

      And("the leader value in the JSON should match the one in the HTTP headers")
      val headerLeader =
        response.originalResponse.headers.find(_.name == "X-Marathon-Leader").get.value.replace("http://", "")
      (info \ "leader").as[String] should be(headerLeader)

      And("the leader should match the value returned by /v2/leader")
      (info \ "leader").as[String] should be(marathon.leader().value.leader)
    }
  }
}

@IntegrationTest
class AuthorizedZooKeeperInfoIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {
  lazy val credentials = "user:secret"
  lazy val digest = org.apache.zookeeper.server.auth.DigestAuthenticationProvider.generateDigest(credentials)

  override val marathonArgs: Map[String, String] = Map("zk" -> s"zk://$credentials@${zkServer.connectUri}/marathon")

  "AuthorizedZookeeperInfo" should {
    "v2/info doesn't include the zk credentials" in {
      When("fetching the info")
      val response = marathon.info

      Then("the response should be successful")
      response.code should be (200)

      val info = response.entityJson

      And("the ZooKeeper info should not contain the ZK credentials")
      (info \ "zookeeper_config" \ "zk").as[String] should not contain "user"
      (info \ "zookeeper_config" \ "zk").as[String] should not contain "secret"
    }
  }
}
