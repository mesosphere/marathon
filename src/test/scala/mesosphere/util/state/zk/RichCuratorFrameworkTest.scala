package mesosphere.util.state.zk

import java.nio.charset.StandardCharsets
import java.util.UUID

import akka.util.ByteString
import mesosphere.UnitTest
import mesosphere.marathon.IntegrationTest
import mesosphere.marathon.integration.setup.StartedZookeeper
import mesosphere.util.PortAllocator
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.{ RetryPolicy, RetrySleeper }
import org.apache.zookeeper.{ KeeperException, ZooDefs }
import org.apache.zookeeper.ZooDefs.Perms
import org.apache.zookeeper.data.{ ACL, Id }
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider
import org.scalatest.ConfigMap

import scala.collection.immutable.Seq
import scala.collection.JavaConversions._
import scala.util.{ Random, Try }

@IntegrationTest
class RichCuratorFrameworkTest extends UnitTest with StartedZookeeper {
  // scalastyle:off magic.number
  val root = Random.alphanumeric.take(10).mkString
  val user = new Id("digest", DigestAuthenticationProvider.generateDigest("super:secret"))
  // scalastyle:on
  lazy val (client, richClient) = {
    val client = CuratorFrameworkFactory.newClient(config.zkHostAndPort, new RetryPolicy {
      override def allowRetry(retryCount: Int, elapsedTimeMs: Long, sleeper: RetrySleeper): Boolean = false
    })
    client.start()
    client.getZookeeperClient.getZooKeeper.addAuthInfo(user.getScheme, user.getId.getBytes(StandardCharsets.UTF_8))
    Try(client.create().forPath(s"/$root"))
    val chroot = client.usingNamespace(root)
    (chroot, new RichCuratorFramework(chroot))
  }

  override protected def beforeAll(configMap: ConfigMap): Unit = {
    super.beforeAll(configMap + ("zkPort" -> PortAllocator.ephemeralPort().toString))
  }

  after {
    client.getChildren.forPath("/").map { child =>
      client.delete().deletingChildrenIfNeeded().forPath(s"/$child")
    }
  }

  "RichCuratorFramework" should {
    "be able to create a simple node" in {
      richClient.create("/1").futureValue should equal(s"/1")
      val childrenData = client.children("/").futureValue
      childrenData.children should contain only ("1")
      childrenData.path should equal("/")
      childrenData.stat.getVersion should equal(0)
      childrenData.stat.getEphemeralOwner should equal(0)
      childrenData.stat.getNumChildren should equal(1)
    }
    "be able to create a simple node with data" in {
      richClient.create("/2", data = Some(ByteString("abc"))).futureValue should equal(s"/2")
      client.data("/2").futureValue.data should equal(ByteString("abc"))
      val childrenData = client.children("/").futureValue
      childrenData.children should contain only ("2")
      childrenData.path should equal("/")
      childrenData.stat.getVersion should equal(0)
      childrenData.stat.getEphemeralOwner should equal(0)
      childrenData.stat.getNumChildren should equal(1)
    }
    "be able to create a tree with data" in {
      richClient.create(
        "/3/4/5/6",
        data = Some(ByteString("def")),
        creatingParentContainersIfNeeded = true).futureValue should equal("/3/4/5/6")
      richClient.data("/3/4/5/6").futureValue.data should equal(ByteString("def"))
    }
    "fail when creating a nested node when the parent doesn't exist and createParent isn't enabled" in {
      val failure = richClient.create("/4/5/6").failed.futureValue
      failure shouldBe a[KeeperException.NoNodeException]
    }
    "fail when creating a node with an invalid name" in {
      val failure = richClient.create(UUID.randomUUID.toString).failed.futureValue
      failure shouldBe a[IllegalArgumentException]
    }
    "be able to delete a node" in {
      richClient.create("/4").futureValue
      richClient.delete("/4").futureValue should equal("/4")
      richClient.children("/").futureValue.children should be('empty)
    }
    "be able to delete a tree of nodes" in {
      richClient.create("/4/5/6", creatingParentsIfNeeded = true).futureValue
      richClient.delete("/4", deletingChildrenIfNeeded = true).futureValue should equal("/4")
      richClient.children("/").futureValue.children should be('empty)
    }
    "be able to check for the existence of a node" in {
      richClient.create("/5").futureValue
      val exists = richClient.exists("/5").futureValue
      exists.path should equal("/5")
      exists.stat.getVersion should equal(0)
    }
    "be able to check for the existence of a node in a nested path" in {
      richClient.create("/5/6/7", creatingParentsIfNeeded = true).futureValue
      val exists = richClient.exists("/5/6/7").futureValue
      exists.path should equal("/5/6/7")
      exists.stat.getVersion should equal(0)
    }
    "be able to set data on an existing node" in {
      richClient.create("/5/6/7", creatingParentsIfNeeded = true).futureValue
      val result = richClient.setData("/5/6/7", ByteString("abc")).futureValue
      result.path should equal("/5/6/7")
      result.stat.getVersion should equal(1)
      result.stat.getDataLength should equal(ByteString("abc").toArray.length)
      richClient.data("/5/6/7").futureValue.data should equal(ByteString("abc"))
    }
    "be able to sync" in {
      richClient.create("/sync").futureValue
      richClient.sync("/sync").futureValue should be('empty)
    }
    "be able to get an ACL" in {
      val acl = new ACL(Perms.ALL, user)
      val readAcl = ZooDefs.Ids.READ_ACL_UNSAFE.toIndexedSeq
      richClient.create("/acl", acls = acl +: readAcl).futureValue
      richClient.acl("/acl").futureValue should equal(acl +: readAcl)
    }
    "be able to set an ACL" in {
      val acls = Seq(new ACL(Perms.ALL, user))
      richClient.create("/acl", acls = ZooDefs.Ids.OPEN_ACL_UNSAFE.toIndexedSeq).futureValue
      richClient.setAcl("/acl", acls).futureValue
      richClient.acl("/acl").futureValue should equal(acls)
    }
  }
}
