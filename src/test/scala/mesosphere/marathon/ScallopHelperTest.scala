package mesosphere.marathon

import mesosphere.UnitTest

class ScallopHelperTest extends UnitTest {
  val conf = AllConf(
    "--master",
    "zk://super:secret@127.0.0.1:2181/master",
    "--zk",
    "zk://also:special@localhost:2181/marathon",
    "--mesos_role",
    "super"
  )

  "return the defined scallop options for a ScallopConf, specified or not" in {
    val opts = ScallopHelper.scallopOptions(conf).groupBy(_.name).mapValues(_.head)
    opts.keys should contain("zk")
    opts.keys should contain("mesos_role")
    opts.keys should contain("hostname")
    opts.keys shouldNot contain("number") // this is a builder method which returns a ScallopOption
    opts.keys shouldNot contain("opt") // this is a builder method which returns a ScallopOption
    opts("zk").apply().toString shouldBe (conf.zooKeeperUrl().toString)
  }
}
