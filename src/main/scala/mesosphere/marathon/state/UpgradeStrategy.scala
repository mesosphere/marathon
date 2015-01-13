package mesosphere.marathon.state

import mesosphere.marathon.Protos._

case class UpgradeStrategy(minimumHealthCapacity: Double, maximumOverCapacity: Double = 1.0) {
  def toProto: UpgradeStrategyDefinition = UpgradeStrategyDefinition.newBuilder
    .setMinimumHealthCapacity(minimumHealthCapacity)
    .setMaximumOverCapacity(maximumOverCapacity)
    .build
}

object UpgradeStrategy {
  def empty: UpgradeStrategy = UpgradeStrategy(1)
  def fromProto(upgradeStrategy: UpgradeStrategyDefinition): UpgradeStrategy =
    UpgradeStrategy(
      upgradeStrategy.getMinimumHealthCapacity,
      upgradeStrategy.getMaximumOverCapacity
    )
}
