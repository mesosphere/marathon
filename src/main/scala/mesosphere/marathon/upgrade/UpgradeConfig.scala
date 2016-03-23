package mesosphere.marathon.upgrade

import org.rogach.scallop.ScallopConf
import scala.concurrent.duration._

import scala.concurrent.duration.FiniteDuration

/**
  * Config parameters for the upgrade module.
  */
trait UpgradeConfig extends ScallopConf {
  //scalastyle:off magic.number

  lazy val killsPerBatch = opt[Int]("killsPerBatch",
    descr = "INTERNAL TUNING PARAMETER: " +
      "The number of kills that can be issued in one batch size.",
    noshort = true,
    hidden = true,
    default = Some(100)
  )

  /**
    * Defines the duration of one batch cycle
    */
  lazy val killBatchDuration = opt[Long]("killBatchDuration",
    descr = "INTERNAL TUNING PARAMETER: " +
      "The duration of one batch cycle in milliseconds.",
    noshort = true,
    hidden = true,
    default = Some(10000L) //10 seconds
  )

  def killBatchCycle: FiniteDuration = killBatchDuration().millis
  def killBatchSize: Int = killsPerBatch()

}
