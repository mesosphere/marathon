package mesosphere.marathon

import org.apache.mesos.SchedulerDriver

/**
  * Holds the current instances of the driver and the scheduler.
  *
  * Usually, you would just wire in the driver directly. Unfortunately, the driver can change over the life
  * time of the application. Whenever a process gains leadership, a new driver is created and has to be
  * made available to the already wired dependencies.
  *
  * A better alternative would be to tear down all state/actors when loosing leadership and recreate/rewire
  * everything when gaining leadership. This would require more code changes, though.
  */
class MarathonSchedulerDriverHolder {
  @volatile
  var driver: Option[SchedulerDriver] = None
}