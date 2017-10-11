package mesosphere.marathon
package raml

trait UpgradeStrategyConversion {

  def asRaml(strategy: state.UpgradeStrategy): UpgradeStrategy = {
    UpgradeStrategy(strategy.maximumOverCapacity, strategy.minimumHealthCapacity)
  }

}

object UpgradeStrategyConversion extends UpgradeStrategyConversion
