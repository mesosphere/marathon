package mesosphere.marathon
package integration

import java.io.File

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.MarathonFacade
import mesosphere.marathon.integration.facades.MarathonFacade.extractDeploymentIds
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.raml.{ App, AppUpdate }
import mesosphere.marathon.state.PathId._

import scala.concurrent.duration._

/**
  * Do not add tests to this class. See the notes in [[NonDestructiveLeaderIntegrationTest]].
  */
abstract class LeaderIntegrationTest extends AkkaIntegrationTest with MarathonClusterTest {

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
  "NonDestructiveLeader" should {
    "all nodes return the same leader" in {
      Given("a leader has been elected")
      WaitTestSupport.waitUntil("a leader has been elected") { marathon.leader().code == 200 }

      When("calling /v2/leader on all nodes of a cluster")
      val results = marathonFacades.map(marathon => marathon.leader())

      Then("the requests should all be successful")
      results.foreach(_.code should be (200))

      And("they should all be the same")
      results.map(_.value).distinct should have length 1
    }

    "all nodes return a redirect on GET /" in {
      Given("a leader has been elected")
      WaitTestSupport.waitUntil("a leader has been elected") { marathon.leader().code == 200 }

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
}

@IntegrationTest
class DeathUponAbdicationLeaderIntegrationTest extends AkkaIntegrationTest with MarathonFixture with MesosClusterTest with ZookeeperServerTest {
  "LeaderAbdicationDeath" should {
    "the leader abdicates and dies when it receives a DELETE" in withMarathon("death-abdication") { (server, f) =>
      Given("a leader")
      WaitTestSupport.waitUntil("a leader has been elected") {
        f.marathon.leader().code == 200
      }
      val leader = f.marathon.leader().value

      When("calling DELETE /v2/leader")
      val result = f.marathon.abdicate()

      Then("the request should be successful")
      result should be(OK)
      (result.entityJson \ "message").as[String] should be("Leadership abdicated")

      And("the leader must have died")
      WaitTestSupport.waitUntil("the leading marathon dies changes", 30.seconds) {
        !server.isRunning()
      }
    }
  }
}

@IntegrationTest
class ReelectionLeaderIntegrationTest extends LeaderIntegrationTest {

  val zkTimeout = 2000L
  override val marathonArgs: Map[String, String] = Map(
    "zk_timeout" -> s"$zkTimeout"
  )

  override val numAdditionalMarathons = 2

  "Reelecting a leader" should {
    "it survives a small reelection test" in {

      for (_ <- 1 to 3) {
        Given("a leader")
        WaitTestSupport.waitUntil("a leader has been elected") { firstRunningProcess.client.leader().code == 200 }

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
        WaitTestSupport.waitUntil("the leader changes") {
          val result = firstRunningProcess.client.leader()
          result.code == 200 && result.value != leader
        }

        And("all instances agree on the leader")
        WaitTestSupport.waitUntil("all instances agree on the leader") {
          val results = runningServerProcesses.map(_.client.leader())
          results.forall(_.code == 200) && results.map(_.value).distinct.size == 1
        }

        // allow ZK session for former leader to timeout before proceeding
        Thread.sleep((zkTimeout * 2.5).toLong)

        And("the old leader should restart just fine")
        leadingProcess.start().futureValue
      }
    }
  }
}

// Regression test for MARATHON-7458
@IntegrationTest
class KeepAppsRunningDuringAbdicationIntegrationTest extends LeaderIntegrationTest {

  val zkTimeout = 2000L
  override val marathonArgs: Map[String, String] = Map(
    "zk_timeout" -> s"$zkTimeout"
  )

  override val numAdditionalMarathons = 2

  "Abdicating a leader" should {
    "keep all running apps alive" in {

      Given("a leader")
      WaitTestSupport.waitUntil("a leader has been elected") { firstRunningProcess.client.leader().code == 200 }

      // pick the leader to communicate with because it's the only known survivor
      val leader = firstRunningProcess.client.leader().value
      val leadingProcess: LocalMarathon = leadingServerProcess(leader.leader)
      val client = leadingProcess.client

      val app = App("/keepappsrunningduringabdicationintegrationtest", cmd = Some("sleep 1000"))
      val result = marathon.createAppV2(app)
      result should be(Created)
      extractDeploymentIds(result) should have size 1 withClue "Deployment was not triggered"
      waitForDeployment(result)
      val oldInstances = client.tasks(app.id.toPath).value
      oldInstances should have size 1 withClue "Required instance was not started"

      When("calling DELETE /v2/leader")
      val abdicateResult = client.abdicate()

      Then("the request should be successful")
      abdicateResult should be (OK) withClue "Leader was not abdicated"
      (abdicateResult.entityJson \ "message").as[String] should be ("Leadership abdicated")

      And("the leader must have died")
      WaitTestSupport.waitUntil("the former leading marathon process dies", 30.seconds) { !leadingProcess.isRunning() }
      leadingProcess.stop() // already stopped, but still need to clear old state

      And("the leader must have changed")
      WaitTestSupport.waitUntil("the leader changes") {
        val result = firstRunningProcess.client.leader()
        result.code == 200 && result.value != leader
      }

      val newLeader = firstRunningProcess.client.leader().value
      val newLeadingProcess: LocalMarathon = leadingServerProcess(newLeader.leader)
      val newClient = newLeadingProcess.client

      // we should have one survived instance
      newClient.app(app.id.toPath).value.app.instances should be(1) withClue "Previously started app did not survive the abdication"
      val newInstances = newClient.tasks(app.id.toPath).value
      newInstances should have size 1 withClue "Previously started one instance did not survive the abdication"
      newInstances.head.id should be (oldInstances.head.id) withClue "During abdication we started a new instance, instead keeping the old one."
    }
  }
}

// Regression test for MARATHON-7565
@IntegrationTest
class BackupRestoreIntegrationTest extends LeaderIntegrationTest {

  val zkTimeout = 2000L
  override val marathonArgs: Map[String, String] = Map(
    "zk_timeout" -> s"$zkTimeout"
  )

  override val numAdditionalMarathons = 2

  "Abdicating a leader" should {
    "keep all running apps alive" in {

      Given("a leader")
      WaitTestSupport.waitUntil("a leader has been elected") { firstRunningProcess.client.leader().code == 200 }

      // pick the leader to communicate with because it's the only known survivor
      val leader1 = firstRunningProcess.client.leader().value
      val leadingProcess1: LocalMarathon = leadingServerProcess(leader1.leader)
      val client1 = leadingProcess1.client

      val app1 = App("/backuprestoreintegrationtest1delete", cmd = Some("sleep 1000"))
      val app2 = App("/backuprestoreintegrationtest2update", cmd = Some("sleep 1000"))
      val app3 = App("/backuprestoreintegrationtest3new", cmd = Some("sleep 1000"))
      val app4 = App("/backuprestoreintegrationtest4scale", cmd = Some("sleep 1000"))
      val app5 = App("/backuprestoreintegrationtest5deployment", cmd = Some("sleep 1000"), constraints = Set(Seq("hostname", "UNIQUE")), instances = 2)

      val tmpBackupFile = File.createTempFile("marathon", "BackupRestoreIntegrationTest")

      val createApp5Response = client1.createAppV2(app5)
      createApp5Response should be(Created)

      val createApp1Response = client1.createAppV2(app1)
      createApp1Response should be(Created)
      waitForDeployment(createApp1Response)

      val createApp4Response = client1.createAppV2(app4)
      createApp4Response should be(Created)
      waitForDeployment(createApp4Response)

      val createApp2Response = client1.createAppV2(app2)
      createApp2Response should be(Created)
      waitForDeployment(createApp2Response)

      And("calling DELETE /v2/leader with backups")
      val abdicateResult = client1.abdicateWithBackup(tmpBackupFile.getAbsolutePath)

      Then("the request should be successful")
      abdicateResult should be (OK) withClue "Leader was not abdicated"
      (abdicateResult.entityJson \ "message").as[String] should be ("Leadership abdicated")

      And("the leader must have died")
      WaitTestSupport.waitUntil("the former leading marathon process dies", 30.seconds) { !leadingProcess1.isRunning() }
      leadingProcess1.stop() // already stopped, but still need to clear old state

      And("the leader must have changed")
      WaitTestSupport.waitUntil("the leader changes", 30.seconds) {
        val result = firstRunningProcess.client.leader()
        result.code == 200 && result.value != leader1
      }

      val leader2 = firstRunningProcess.client.leader().value
      val leadingProcess2: LocalMarathon = leadingServerProcess(leader2.leader)
      val client2 = leadingProcess2.client
      waitForSSEConnect()

      val deleteApp1Response = client2.deleteApp(app1.id.toPath)
      deleteApp1Response should be(OK)
      waitForDeployment(deleteApp1Response)

      val updateApp2Response = client2.updateApp(app2.id.toPath, AppUpdate(cmd = Some("sleep 2000")))
      updateApp2Response should be(OK)
      waitForDeployment(updateApp2Response)

      val updateApp4Response = client2.updateApp(app2.id.toPath, AppUpdate(cmd = Some("sleep 1500"), instances = Some(3)))
      updateApp4Response should be(OK)
      waitForDeployment(updateApp4Response)

      val createApp3Response = client2.createAppV2(app3)
      createApp3Response should be(Created)
      waitForDeployment(createApp3Response)

      val deleteApp5Response = client2.deleteApp(app3.id.toPath, force = true)
      deleteApp5Response should be(OK)
      waitForDeployment(deleteApp5Response)

      And("calling DELETE /v2/leader with restore")
      val abdicateResult2 = client2.abdicateWithRestore(tmpBackupFile.getAbsolutePath)

      Then("the request should be successful")
      abdicateResult2 should be (OK) withClue "Leader was not abdicated"
      (abdicateResult.entityJson \ "message").as[String] should be ("Leadership abdicated")

      And("the leader must have changed")
      WaitTestSupport.waitUntil("the leader changes", 30.seconds) {
        val result = firstRunningProcess.client.leader()
        result.code == 200 && result.value != leader2
      }

      val leader3 = firstRunningProcess.client.leader().value
      val leadingProcess3: LocalMarathon = leadingServerProcess(leader3.leader)
      val client3 = leadingProcess3.client
      waitForSSEConnect()

      client3.app(app1.id.toPath) should be (OK) withClue "App was not restored correctly"

      val app2Response = client3.app(app2.id.toPath)
      app2Response should be (OK) withClue "App2 was not restored correctly"
      app2Response.value.app.cmd should be (app2.cmd) withClue "App2 was not restored correctly"

      val app3Response = client3.app(app3.id.toPath)
      app3Response should be (NotFound) withClue "App3 was not restored correctly"

      val app4Response = client3.app(app2.id.toPath)
      app4Response should be (OK) withClue "App4 was not restored correctly"
      app4Response.value.app.cmd should be (app2.cmd) withClue "App4 was not restored correctly"
      app4Response.value.app.instances should be (app2.instances) withClue "App4 was not restored correctly"

      val app5Response = client3.app(app2.id.toPath)
      app5Response should be (OK) withClue "App5 was not restored correctly"
      // this app should still be stuck in deployment
      client3.tasks(app5.id.toPath).value.size should be (1) withClue "App5 was not restored correctly"

    }
  }
}

// Regression test for MARATHON-7525
@IntegrationTest
class DeleteAppAndBackupIntegrationTest extends LeaderIntegrationTest {

  val zkTimeout = 2000L
  override val marathonArgs: Map[String, String] = Map(
    "zk_timeout" -> s"$zkTimeout"
  )

  override val numAdditionalMarathons = 2

  "Abdicating a leader" should {
    "keep all running apps alive" in {

      Given("a leader")
      WaitTestSupport.waitUntil("a leader has been elected") { firstRunningProcess.client.leader().code == 200 }

      // pick the leader to communicate with because it's the only known survivor
      val leader = firstRunningProcess.client.leader().value
      val leadingProcess: LocalMarathon = leadingServerProcess(leader.leader)
      val client = leadingProcess.client

      When("Creating an app")
      val app = App("/deleteappandbackupintegrationtest", cmd = Some("sleep 1000"))
      val result = marathon.createAppV2(app)
      result should be(Created)
      extractDeploymentIds(result) should have size 1 withClue "Deployment was not triggered"
      waitForDeployment(result)
      val oldInstances = client.tasks(app.id.toPath).value
      oldInstances should have size 1 withClue "Required instance was not started"

      And("calling DELETE /v2/leader with backups")
      val tmpBackupFile = File.createTempFile("marathon", "DeleteAppAndBackupIntegrationTest")
      val abdicateResult = client.abdicateWithBackup(tmpBackupFile.getAbsolutePath)
      tmpBackupFile.delete()

      Then("the request should be successful")
      abdicateResult should be (OK) withClue "Leader was not abdicated"
      (abdicateResult.entityJson \ "message").as[String] should be ("Leadership abdicated")

      And("the leader must have died")
      WaitTestSupport.waitUntil("the former leading marathon process dies", 30.seconds) { !leadingProcess.isRunning() }
      leadingProcess.stop() // already stopped, but still need to clear old state

      And("the leader must have changed")
      WaitTestSupport.waitUntil("the leader changes", 30.seconds) {
        val result = firstRunningProcess.client.leader()
        result.code == 200 && result.value != leader
      }

      val leader2 = firstRunningProcess.client.leader().value
      val leadingProcess2: LocalMarathon = leadingServerProcess(leader2.leader)
      val client2 = leadingProcess2.client

      And("delete the app")
      val delete = client2.deleteApp(app.id.toPath)
      delete should be(OK)

      When("calling DELETE /v2/leader with backups again")
      val tmpBackupFile2 = File.createTempFile("marathon", "DeleteAppAndBackupIntegrationTest")
      val abdicateResult2 = client2.abdicateWithBackup(tmpBackupFile2.getAbsolutePath)
      tmpBackupFile2.delete()

      Then("the request should be successful")
      abdicateResult2 should be(OK) withClue "Leader was not abdicated"
      (abdicateResult2.entityJson \ "message").as[String] should be("Leadership abdicated")

      And("the leader must have died")
      WaitTestSupport.waitUntil("the former leading marathon process dies", 30.seconds) {
        !leadingProcess2.isRunning()
      }
      leadingProcess2.stop() // already stopped, but still need to clear old state

      And("the leader must have changed")
      WaitTestSupport.waitUntil("the leader changes", 30.seconds) {
        val result = firstRunningProcess.client.leader()
        result.code == 200 && result.value != leader
      }

      val leader3 = firstRunningProcess.client.leader().value
      val leadingProcess3: LocalMarathon = leadingServerProcess(leader3.leader)
      val client3 = leadingProcess3.client

      And("app should not be available")
      client3.app(app.id.toPath) should be(NotFound)
    }
  }
}