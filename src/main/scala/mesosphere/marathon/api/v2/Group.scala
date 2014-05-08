package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v1.AppDefinition
import com.fasterxml.jackson.databind.annotation.{JsonSerialize, JsonDeserialize}
import mesosphere.marathon.api.v2.json.MarathonModule.{SteppingSerializer, SteppingDeserializer}

@JsonDeserialize(using = classOf[SteppingDeserializer], as = classOf[Stepping])
@JsonSerialize(using = classOf[SteppingSerializer])
sealed trait Stepping {
  def count(app: AppDefinition): Int
}

final case class AbsoluteStepping(_count: Int) extends Stepping {
  def count(app: AppDefinition): Int = _count
}

final case class RelativeStepping(factor: Double) extends Stepping {
  def count(app: AppDefinition) = (app.instances * factor).toInt
}

final case class Staged(steps: Seq[Stepping], stepSeconds: Int)

final case class ScalingStrategy(
    maxBatchSize: Int,
    staged: Staged,
    startupTimeout: Int,
    watchPeriod: Int)

final case class Group(
    id: String,
    scaleUpStrategy: ScalingStrategy,
    scaleDownStrategy: ScalingStrategy,
    apps: Seq[AppDefinition])
