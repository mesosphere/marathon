package mesosphere.marathon.integration

import mesosphere.marathon.integration.facades.MarathonFacade
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.PathId
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.{ WatchedEvent, Watcher, ZooKeeper }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
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

  ignore("it survives a small burn-in reelection test - https://github.com/mesosphere/marathon/issues/4215") {
    val random = new scala.util.Random
    for (_ <- 1 to 10) {
      Given("a leader")
      WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }
      val leader = marathon.leader().value

      When("calling DELETE /v2/leader")
      val result = marathon.abdicate()

      Then("the request should be successful")
      result.code should be (200)
      (result.entityJson \ "message").as[String] should be ("Leadership abdicated")

      And("the leader must have changed")
      WaitTestSupport.waitUntil("the leader changes", 30.seconds) {
        val result = marathon.leader()
        result.code == 200 && result.value != leader
      }

      And("all instances agree on the leader")
      WaitTestSupport.waitUntil("all instances agree on the leader", 30.seconds) {
        val results = marathonFacades.map(marathon => marathon.leader())
        results.forall(_.code == 200) && results.map(_.value).distinct.size == 1
      }

      Thread.sleep(random.nextInt(10) * 100L)
    }
  }

  test("the leader sets a tombstone for the old twitter commons leader election") {
    def checkTombstone(): Unit = {
      val watcher = new Watcher { override def process(event: WatchedEvent): Unit = println(event) }
      val zooKeeper = new ZooKeeper(config.zkHostAndPort, 30 * 1000, watcher)

      try {
        Then("there is a tombstone")
        var stat: Option[Stat] = None
        WaitTestSupport.waitUntil("the tombstone is created", 30.seconds) {
          stat = Option(zooKeeper.exists(config.zkPath + "/leader/member_-00000000", false))
          stat.isDefined
        }

        And("the tombstone points to the leader")
        val apiLeader: String = marathon.leader().value.leader
        val tombstoneData = zooKeeper.getData(config.zkPath + "/leader/member_-00000000", false, stat.get)
        new String(tombstoneData, "UTF-8") should equal(apiLeader)
      } finally {
        zooKeeper.close()
      }
    }

    Given("a leader")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }
    val leader = marathon.leader().value

    checkTombstone()

    When("calling DELETE /v2/leader")
    val result = marathon.abdicate()

    Then("the request should be successful")
    result.code should be (200)
    (result.entityJson \ "message").as[String] should be ("Leadership abdicated")

    And("the leader must have changed")
    WaitTestSupport.waitUntil("the leader changes", 30.seconds) { marathon.leader().value != leader }

    checkTombstone()
  }

  ignore("the tombstone stops old instances from becoming leader") {
    // FIXME(jason): https://github.com/mesosphere/marathon/issues/4040
    When("Starting an instance with --leader_election_backend")
    val parameters = List(
      "--master", config.master,
      "--leader_election_backend", "twitter_commons"
    ) ++ extraMarathonParameters
    val twitterCommonsInstancePort = config.marathonPorts.last + 1
    startMarathon(twitterCommonsInstancePort, parameters: _*)

    val facade = new MarathonFacade(s"http://${config.marathonHost}:$twitterCommonsInstancePort", PathId.empty)
    val random = new scala.util.Random

    1.to(10).map { i =>
      Given(s"a leader ($i)")
      WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }
      val leader = marathon.leader().value

      Then(s"it is never the twitter_commons instance ($i)")
      leader.leader.split(":")(1).toInt should not be twitterCommonsInstancePort

      And(s"the twitter_commons instance knows the real leader ($i)")
      WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) {
        val result = facade.leader()
        result.code == 200 && result.value == leader
      }

      When(s"calling DELETE /v2/leader ($i)")
      val result = marathon.abdicate()

      Then(s"the request should be successful ($i)")
      result.code should be (200)
      (result.entityJson \ "message").as[String] should be ("Leadership abdicated")

      And(s"the leader must have changed ($i)")
      WaitTestSupport.waitUntil("the leader changes", 30.seconds) {
        val result = marathon.leader()
        result.code == 200 && result.value != leader
      }

      Thread.sleep(random.nextInt(10) * 100L)
    }
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
