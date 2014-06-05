package mesosphere.marathon

import org.apache.mesos.Protos.{ FrameworkID, FrameworkInfo }
import org.apache.mesos.{ SchedulerDriver, MesosSchedulerDriver }

/**
  * Wrapper class for the scheduler
  */
object MarathonSchedulerDriver {

  var driver: Option[SchedulerDriver] = None

  val frameworkName = "marathon-" + Main.properties.getProperty("marathon.version")

  def newDriver(config: MarathonConf,
                scheduler: MarathonScheduler,
                frameworkId: Option[FrameworkID]): SchedulerDriver = {
    val builder = FrameworkInfo.newBuilder()
      .setName(frameworkName)
      .setFailoverTimeout(config.mesosFailoverTimeout())
      .setUser(config.mesosUser())
      .setCheckpoint(config.checkpoint())

    // Set the role, if provided.
    config.mesosRole.get.foreach(builder.setRole)

    // Set the ID, if provided
    frameworkId.foreach(builder.setId)

    val newDriver = new MesosSchedulerDriver(
      scheduler,
      builder.build(),
      config.mesosMaster()
    )
    driver = Some(newDriver)
    newDriver
  }
}
