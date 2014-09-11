package mesosphere.marathon

import org.rogach.scallop.ScallopConf
import scala.util.Try

class ZookeeperConfTest extends MarathonSpec {

  test("emptyConfigurationGivesDefaultValues") {
    val opts = conf()
    assert(opts.zooKeeperHostString.isDefined)
    assert(opts.zooKeeperPath.isDefined)
    assert(opts.zooKeeperTimeout.isDefined)
  }

  test("hostStateParameterGetParsed") {
    val host = "host1:123"
    val timeout = 123
    val state = "/test"
    val opts = conf("--zk_hosts", host, "--zk_timeout", timeout.toString, "--zk_state", state)

    assert(opts.zooKeeperHostString() == host)
    assert(opts.zooKeeperTimeout() == timeout)
    assert(opts.zooKeeperPath() == state)
    assert(opts.zkURL == s"zk://$host$state")
  }

  test("urlParameterGetParsed") {
    val url = "zk://host1:123,host2,host3:312/path"
    val opts = conf("--zk", url)
    assert(opts.zkURL == url)
    assert(opts.zkHosts == "host1:123,host2,host3:312")
    assert(opts.zkPath == "/path")
  }

  test("urlParameterWithAuthGetParsed") {
    val url = "zk://user1:pass1@host1:123,host2,host3:312/path"
    val opts = conf("--zk", url)
    assert(opts.zkURL == url)
    assert(opts.zkHosts == "host1:123,host2,host3:312")
    assert(opts.zkPath == "/path")
  }

  test("wrongURLIsNotParsed") {
    assert(Try(conf("--zk", "zk://host1:foo/path")).isFailure, "No port number")
    assert(Try(conf("--zk", "zk://host1")).isFailure, "No path")
  }

  def conf(args: String*) = {
    val opts = new ScallopConf(args) with ZookeeperConf {
      //scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
    }
    opts.afterInit()
    opts
  }
}
