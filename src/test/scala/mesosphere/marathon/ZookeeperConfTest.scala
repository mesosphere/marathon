package mesosphere.marathon

import org.rogach.scallop.ScallopConf
import org.junit.Test
import org.junit.Assert._
import scala.util.Try

/**
 * Date: 14.04.14
 * Time: 10:44
 */
class ZookeeperConfTest {

  @Test
  def emptyConfigurationGivesDefaultValues() {
    val opts = conf()
    assertFalse(opts.zooKeeperHostString.isEmpty)
    assertFalse(opts.zooKeeperPath.isEmpty)
    assertFalse(opts.zooKeeperTimeout.isEmpty)
  }

  @Test
  def hostStateParameterGetParsed() {
    val host = "host1:123"
    val timeout = 123
    val state = "/test"
    val opts = conf("--zk_hosts", host, "--zk_timeout", timeout.toString, "--zk_state", state )

    assertTrue(opts.zooKeeperHostString() == host)
    assertTrue(opts.zooKeeperTimeout() == timeout)
    assertTrue(opts.zooKeeperPath() == state)
    assertEquals(opts.zkURL, s"zk://$host$state")
  }

  @Test
  def urlParameterGetParsed() {
    val url = "zk://host1:123,host2,host3:312/path"
    val opts = conf("--zk_marathon", url)
    assertEquals(opts.zkURL, url)
    assertEquals(opts.zkHosts, "host1:123,host2,host3:312")
    assertEquals(opts.zkPath, "/path")
  }

  @Test
  def wrongURLIsNotParsed() {
    assertFalse("No port number",  Try(conf("--zk_marathon", "zk://host1:foo/path")).isSuccess)
    assertFalse("No path", Try(conf("--zk_marathon", "zk://host1")).isSuccess)
  }

  def conf(args:String *) = {
    val opts = new ScallopConf(args) with ZookeeperConf {
      //scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
    }
    opts.afterInit()
    opts
  }
}
