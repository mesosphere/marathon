package mesosphere.marathon
package integration

import com.mesosphere.utils.mesos.MesosClusterTest
import com.mesosphere.utils.zookeeper.ZookeeperServerTest
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.core.base.JvmExitsCrashStrategy
import mesosphere.marathon.core.storage.store.impl.zk.RichCuratorFramework
import mesosphere.marathon.integration.setup.LocalMarathon
import org.scalatest.concurrent.Eventually

import scala.concurrent.Await

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
      val client = RichCuratorFramework(zkClient(), JvmExitsCrashStrategy)
      try {
        val returnedPath = Await.result(client.create(path, creatingParentsIfNeeded = true), patienceConfig.timeout)
        returnedPath should equal (path)
      } finally {
        client.close()
      }

      When("Marathon starts up and becomes a leader")
      val marathonServer = LocalMarathon(suiteName = suiteName, masterUrl = mesosMasterZkUrl,
        zkUrl = s"zk://${zkserver.connectUrl}/$namespace")
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
