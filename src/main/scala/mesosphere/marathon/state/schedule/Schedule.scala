package mesosphere.marathon.state.schedule

/**
  * Defines the strategy of how to schedule instances for a run spec
  */
case class Schedule(strategy: SchedulingStrategy)

object Schedule {
  /**
    * The default schedule to be used for migrating old RunSpecs.
    */
  val DefaultSchedule: Schedule = Schedule(Continuous)
}
