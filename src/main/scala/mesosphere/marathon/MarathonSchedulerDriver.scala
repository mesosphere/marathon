package mesosphere.marathon

import org.apache.mesos.Protos.{ FrameworkID, FrameworkInfo, Credential }
import org.apache.mesos.{ SchedulerDriver, MesosSchedulerDriver }

import com.google.protobuf.ByteString;

/**
  * Wrapper class for the scheduler
  */
object MarathonSchedulerDriver {

  var driver: Option[SchedulerDriver] = None

  var scheduler: Option[MarathonScheduler] = None

  val frameworkName = s"marathon-${BuildInfo.version}"

  def newDriver(config: MarathonConf,
                newScheduler: MarathonScheduler,
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

    var newDriver: MesosSchedulerDriver = null
    if (config.mesosAuthUser.get.getOrElse("") != "" && config.mesosAuthPassword.get.getOrElse("") != "") {
      val credential = Credential.newBuilder()
        .setPrincipal(config.mesosAuthUser())
        .setSecret(ByteString.copyFromUtf8(config.mesosAuthPassword()))
        .build()

      newDriver = new MesosSchedulerDriver(
        newScheduler,
        builder.build(),
        config.mesosMaster(),
        credential
      )
    }
    else {
      newDriver = new MesosSchedulerDriver(
        newScheduler,
        builder.build(),
        config.mesosMaster()
      )
    }

    driver = Some(newDriver)
    scheduler = Some(newScheduler)
    newDriver
  }
}
