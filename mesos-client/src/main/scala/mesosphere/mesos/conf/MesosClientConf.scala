package mesosphere.mesos.conf

import scala.concurrent.duration._

/**
  * Configuring mesos v1 client
  *
  * @param master The URL of the Mesos master
  * @param sourceBufferSize Buffer size of the mesos source im messages
  * @param redirectRetires Number of retries to follow mesos master redirect
  * @param idleTimeout Time in seconds between two processed elements exceeds the provided timeout then the connection to mesos
  *                    is interrupted. Is usually set to approx. 5 hear beats.
  */
case class MesosClientConf(master: String,
                     sourceBufferSize: Int = 10,
                     redirectRetires: Int = 3,
                     idleTimeout: FiniteDuration = 75.seconds)