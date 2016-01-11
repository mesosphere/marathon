package mesosphere.marathon.state

import mesosphere.marathon.Protos._

import com.wix.accord.dsl._

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

  implicit val updateStrategyValidator = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity is between(0.0, 1.0)
    strategy.maximumOverCapacity is between(0.0, 1.0)
  }
}
