package mesosphere.marathon.state

import com.wix.accord.Validator
import mesosphere.marathon.Protos._

import com.wix.accord.dsl._

case class UpgradeStrategy(minimumHealthCapacity: Double, maximumOverCapacity: Double = 1.0) {
  def toProto: UpgradeStrategyDefinition = UpgradeStrategyDefinition.newBuilder
    .setMinimumHealthCapacity(minimumHealthCapacity)
    .setMaximumOverCapacity(maximumOverCapacity)
    .build
}

object UpgradeStrategy {
  val empty: UpgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 1)
  def forResidentTasks: UpgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0.5, maximumOverCapacity = 0)
  def fromProto(upgradeStrategy: UpgradeStrategyDefinition): UpgradeStrategy =
    UpgradeStrategy(
      upgradeStrategy.getMinimumHealthCapacity,
      upgradeStrategy.getMaximumOverCapacity
    )

  implicit val updateStrategyValidator = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity is between(0.0, 1.0)
    strategy.maximumOverCapacity is between(0.0, 1.0)
  }

  lazy val validForResidentTasks: Validator[UpgradeStrategy] = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity should be <= 0.5
    strategy.maximumOverCapacity should be == 0.0
  }

  lazy val validForSingleInstanceApps: Validator[UpgradeStrategy] = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity should be == 0.0
    strategy.maximumOverCapacity should be == 0.0
  }
}
