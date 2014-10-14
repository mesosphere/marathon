package mesosphere.marathon.state

import mesosphere.marathon.Protos._

case class UpgradeStrategy(minimumHealthCapacity: Double) {
  def toProto: UpgradeStrategyDefinition = UpgradeStrategyDefinition.newBuilder
    .setMinimumHealthCapacity(minimumHealthCapacity)
    .build
}

object UpgradeStrategy {
  def empty: UpgradeStrategy = UpgradeStrategy(1)
  def fromProto(upgradeStrategy: UpgradeStrategyDefinition): UpgradeStrategy =
    UpgradeStrategy(upgradeStrategy.getMinimumHealthCapacity)
}
