package mesosphere.marathon
package core.instance

sealed trait InstancePhase

object InstancePhase {
  case object Scheduled extends InstancePhase
  case object Launching extends InstancePhase
  case object Active extends InstancePhase
  case object Terminal extends InstancePhase
}
