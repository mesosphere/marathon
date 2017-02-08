package mesosphere.marathon.state.schedule

import scala.concurrent.duration.FiniteDuration

/**
  * A [[SchedulingStrategy]] defines how, if, and when instances of a [[mesosphere.marathon.state.RunSpec]] are
  * scheduled to be launched.
  */
sealed trait SchedulingStrategy

/**
  * A [[Continuous]] [[SchedulingStrategy]] has the goal to continuously have a designated amount of instances running.
  * Whenever an instance terminates or is not considered active anymore, this strategy will schedule a new instance as a
  * replacement. Note that the decision of when an instance is considered inactive due to being unreachable for too long
  * is covered in the according [[mesosphere.marathon.state.UnreachableStrategy]].
  */
case object Continuous extends SchedulingStrategy

/**
  * A [[Manual]] [[SchedulingStrategy]] will only schedule instances to be launched upon user request. If such an
  * instance fails, otherwise terminates unexpectedly or is considered inactive (see
  * [[mesosphere.marathon.state.UnreachableStrategy]]), this strategy will eventually launch a new instance as a
  * replacement, if prerequisites are given.
  */
case class Manual(cancelingBehavior: CancelingBehavior) extends SchedulingStrategy

/**
  * A [[Periodic]] [[SchedulingStrategy]] will periodically schedule instances to be launched according to a given cron
  * expression. Specifically, it will create an attempt at each point in time as defined by the cron expression. Each
  * attempt will be started with an implicit [[cancelingBehavior]] configuration that will prevent new instances being
  * launched after the next attempt was triggered by the cron.
  */
case class Periodic(cron: String, cancelingBehavior: CancelingBehavior) extends SchedulingStrategy

case class CancelingBehavior(
  stopTryingAfterNumFailures: Int,
  stopTryingAfter: FiniteDuration,
  maxDurationPerInstance: FiniteDuration)
