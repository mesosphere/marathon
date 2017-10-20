package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.{ LocalMarathon, MesosClusterTest, ZookeeperServerTest }
import org.scalatest.concurrent.Eventually

import scala.concurrent.Await

@IntegrationTest
class MigrationInterruptedIntegrationTest
    extends AkkaIntegrationTest
    with MesosClusterTest
    with ZookeeperServerTest
    with Eventually {

  "Marathon" should {
    "fail to start if migration was interrupted" in {
      Given("there is a migration flag in ZooKeeper")
      val namespace = s"marathon-$suiteName"
      val path = s"/$namespace/state/migration-in-progress"
      val client = zkClient()
      try {
        val returnedPath = Await.result(client.create(path, creatingParentsIfNeeded = true), patienceConfig.timeout)
        returnedPath should equal (path)
      } finally {
        client.close()
      }

      When("Marathon starts up and becomes a leader")
      val marathonServer = LocalMarathon(autoStart = false, suiteName = suiteName, masterUrl = mesosMasterUrl,
        zkUrl = s"zk://${zkServer.connectUri}/$namespace")
      marathonServer.create()

      Then("it fails to start")
      try {
        eventually {
          marathonServer.isRunning() should be(false)
        } withClue "Marathon did not suicide because of lingering migration flag."
        marathonServer.exitValue().get should be > 0 withClue "Marathon exited with 0 instead of an error code > 0."
      } finally {
        marathonServer.stop().futureValue
      }
    }
  }
}
