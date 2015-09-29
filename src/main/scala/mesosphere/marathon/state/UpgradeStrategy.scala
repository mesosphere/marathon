package mesosphere.marathon.state

import mesosphere.marathon.Protos._

import scala.concurrent.duration._

case class UpgradeStrategy(minimumHealthCapacity: Double, maximumOverCapacity: Double = 1.0,
                           killOldTasksDelay: FiniteDuration = UpgradeStrategy.DefaultKillOldTasksDelay) {
  def toProto: UpgradeStrategyDefinition = UpgradeStrategyDefinition.newBuilder
    .setMinimumHealthCapacity(minimumHealthCapacity)
    .setMaximumOverCapacity(maximumOverCapacity)
    .setKillOldTasksDelaySeconds(killOldTasksDelay.toSeconds.toInt)
    .build
}

object UpgradeStrategy {
  val DefaultKillOldTasksDelay = 0.seconds
  def empty: UpgradeStrategy = UpgradeStrategy(1)
  def fromProto(upgradeStrategy: UpgradeStrategyDefinition): UpgradeStrategy =
    UpgradeStrategy(
      upgradeStrategy.getMinimumHealthCapacity,
      upgradeStrategy.getMaximumOverCapacity,
      upgradeStrategy.getKillOldTasksDelaySeconds.seconds
    )
}
