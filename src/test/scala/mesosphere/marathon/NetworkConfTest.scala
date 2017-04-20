package mesosphere.marathon

import mesosphere.UnitTest
import org.rogach.scallop.ScallopConf
import scala.util.{ Try, Failure }

class NetworkConfTest extends UnitTest {
  class NetworkConfImpl(args: String*) extends ScallopConf(args) with NetworkConf {
    override def onError(e: Throwable): Unit = throw e
  }

  "NetworkConf" should {
    "reject invalid mesos_bridge_name" in {
      val opts = new NetworkConfImpl("--mesos_bridge_name", "invalid network name")

      val Failure(ex) = Try(opts.verify())
      ex.getMessage shouldBe (NetworkConf.NetworkNameFailedMessage)
    }

    "allow valid mesos_bridge_name" in {
      val opts = new NetworkConfImpl("--mesos_bridge_name", "valid-n3tw0rk-name")

      opts.verify()
    }
  }
}
