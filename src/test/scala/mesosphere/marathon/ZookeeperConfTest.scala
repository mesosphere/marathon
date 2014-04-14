package mesosphere.marathon

import org.rogach.scallop.ScallopConf
import org.junit.Test
import org.junit.Assert._

/**
 * Date: 14.04.14
 * Time: 10:44
 */
class ZookeeperConfTest {

  @Test
  def emptyConfigurationGivesDefaultValues() {
    val opts = conf()
    assertFalse(opts.zooKeeperHostAddresses.isEmpty)
    assertFalse(opts.zooKeeperLeaderPath.isEmpty)
    assertFalse(opts.zooKeeperServerSetPath.isEmpty)
  }

  @Test
  def parameterGetParsed() {
    val host = "zk://host1:123/path"
    val timeout = 123
    val state = "/test"
    val opts = conf("--zk_hosts", host, "--zk_timeout", timeout.toString, "--zk_state", state )

    assertTrue(opts.zooKeeperHostString() == host)
    assertTrue(opts.zooKeeperTimeout() == timeout)
    assertTrue(opts.zooKeeperPath() == state)
  }

  @Test
  def hostParameterUsesPath() {

  }

  def conf(args:String *) = {
    val opts = new ScallopConf(args) with ZookeeperConf
    opts.afterInit()
    opts
  }
}
