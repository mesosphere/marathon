package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationFunTest
import mesosphere.marathon.integration.facades.MarathonFacade
import mesosphere.marathon.integration.setup._
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.{ WatchedEvent, Watcher, ZooKeeper }
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.duration._

/**
  * Do not add tests to this class. See the notes in [[NonDestructiveLeaderIntegrationTest]].
  */
abstract class LeaderIntegrationTest extends AkkaIntegrationFunTest with MarathonClusterTest {

  protected def nonLeader(leader: String): MarathonFacade = {
    marathonFacades.find(!_.url.contains(leader)).get
  }

  protected def leadingServerProcess(leader: String): LocalMarathon =
    (additionalMarathons :+ marathonServer).find(_.client.url.contains(leader)).getOrElse(
      fail("could not determine the which marathon process was running as leader")
    )

  protected def runningServerProcesses: Seq[LocalMarathon] =
    (additionalMarathons :+ marathonServer).filter(_.isRunning())

  protected def firstRunningProcess = runningServerProcesses.headOption.getOrElse(
    fail("there are no marathon servers running")
  )
}

/**
  * Tests in this suite MUST NOT do anything to cause a cluster servers to die (like abdication).
  * If you need to write such a destructive test then subclass [[LeaderIntegrationTest]] and add
  * your **single** destructive test case to it, as per the other test suites in this file.
  */
@IntegrationTest
class NonDestructiveLeaderIntegrationTest extends LeaderIntegrationTest {
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
    val results = marathonFacades.map { marathon =>
      val url = new java.net.URL(s"${marathon.url}/")
      val httpConnection = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
      httpConnection.setInstanceFollowRedirects(false)
      httpConnection.connect()
      httpConnection
    }

    Then("all nodes send a redirect")
    results.foreach { connection =>
      connection.getResponseCode should be(302) withClue s"Connection to ${connection.getURL} was not a redirect."
    }
  }
}

@IntegrationTest
class DeathUponAbdicationLeaderIntegrationTest extends LeaderIntegrationTest {
  test("the leader abdicates and dies when it receives a DELETE") {
    Given("a leader")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) {
      marathon.leader().code == 200
    }
    val leader = marathon.leader().value
    val leadingServer = leadingServerProcess(leader.leader)

    When("calling DELETE /v2/leader")
    val result = marathon.abdicate()

    Then("the request should be successful")
    result.code should be(200)
    (result.entityJson \ "message").as[String] should be("Leadership abdicated")

    And("the leader must have died")
    WaitTestSupport.waitUntil("the leading marathon dies changes", 30.seconds) {
      !leadingServer.isRunning()
    }
  }
}

@IntegrationTest
class TombstoneLeaderIntegrationTest extends LeaderIntegrationTest {
  test("the leader sets a tombstone for the old twitter commons leader election") {
    Given("a leader")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstRunningProcess.client.leader().code == 200 }

    val leader = marathon.leader()
    val secondary = nonLeader(leader.value.leader) // need to communicate with someone after the leader dies

    def checkTombstone(): Unit = {
      val watcher = new Watcher { override def process(event: WatchedEvent): Unit = println(event) }
      val zooKeeper = new ZooKeeper(zkServer.connectUri, 30 * 1000, watcher)

      try {
        Then("there is a tombstone")
        var stat: Option[Stat] = None
        WaitTestSupport.waitUntil("the tombstone is created", 30.seconds) {
          stat = Option(zooKeeper.exists("/marathon/leader/member_-00000000", false))
          stat.isDefined
        }

        And("the tombstone points to the leader")
        val apiLeader: String = secondary.leader().value.leader
        val tombstoneData = zooKeeper.getData("/marathon/leader/member_-00000000", false, stat.get)
        new String(tombstoneData, "UTF-8") should equal(apiLeader)
      } finally {
        zooKeeper.close()
      }
    }

    checkTombstone()

    When("calling DELETE /v2/leader")
    val leadingProcess = leadingServerProcess(leader.value.leader)
    val result = marathon.abdicate()

    Then("the request should be successful")
    result.code should be (200)
    (result.entityJson \ "message").as[String] should be ("Leadership abdicated")

    And("the leader must have died")
    WaitTestSupport.waitUntil("the marathon process dies", 30.seconds) { !leadingProcess.isRunning() }

    checkTombstone()
  }
}

@IntegrationTest
class ReelectionLeaderIntegrationTest extends LeaderIntegrationTest {

  override val marathonArgs: Map[String, String] = Map(
    "zk_timeout" -> "2000"
  )

  override val numAdditionalMarathons = 2

  test("it survives a small reelection test") {

    for (_ <- 1 to 15) {
      Given("a leader")
      WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstRunningProcess.client.leader().code == 200 }

      // pick the leader to communicate with because it's the only known survivor
      val leader = firstRunningProcess.client.leader().value
      val leadingProcess: LocalMarathon = leadingServerProcess(leader.leader)
      val client = leadingProcess.client

      When("calling DELETE /v2/leader")
      val result = client.abdicate()

      Then("the request should be successful")
      result.code should be (200)
      (result.entityJson \ "message").as[String] should be ("Leadership abdicated")

      And("the leader must have died")
      WaitTestSupport.waitUntil("the former leading marathon process dies", 30.seconds) { !leadingProcess.isRunning() }
      leadingProcess.stop() // already stopped, but still need to clear old state

      And("the leader must have changed")
      WaitTestSupport.waitUntil("the leader changes", 30.seconds) {
        val result = firstRunningProcess.client.leader()
        result.code == 200 && result.value != leader
      }

      And("all instances agree on the leader")
      WaitTestSupport.waitUntil("all instances agree on the leader", 30.seconds) {
        val results = runningServerProcesses.map(_.client.leader())
        results.forall(_.code == 200) && results.map(_.value).distinct.size == 1
      }

      // allow ZK session for former leader to timeout before proceeding
      Thread.sleep(2000L)

      And("the old leader should restart just fine")
      leadingProcess.start().futureValue(Timeout(60.seconds))

    }
  }
}
