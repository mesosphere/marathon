package mesosphere.mesos.conf

import org.rogach.scallop.ScallopConf

/**
  * Configuring mesos v1 client
  */
class MesosConf(args: Seq[String]) extends ScallopConf(args) {

  val mesosMaster = opt[String](
    "master",
    descr = "The URL of the Mesos master",
    required = true,
    noshort = true)

  val sourceBufferSize = opt[Int](
    "buffer_size",
    descr = "Buffer size of the mesos source im messages",
    required = false,
    noshort = true,
    hidden = true,
    default = Some(10)
  )

  val redirectRetires = opt[Int](
    "redirect_retries",
    descr = "Number of retries to follow mesos master redirect",
    required = false,
    noshort = true,
    hidden = true,
    default = Some(3)
  )

  val idleTimeout = opt[Int](
    "idle_timeout",
    descr = "Time in seconds between two processed elements exceeds the provided timeout then the connection to mesos " +
      "is interrupted. Is usually set to approx. 5 hear beats.",
    required = false,
    noshort = true,
    default = Some(75)
  )

  verify()

  private val mesosMasterUri: java.net.URI = new java.net.URI(s"my://${mesosMaster()}")
  val mesosMasterHost: String = mesosMasterUri.getHost()
  val mesosMasterPort: Int = mesosMasterUri.getPort()
}