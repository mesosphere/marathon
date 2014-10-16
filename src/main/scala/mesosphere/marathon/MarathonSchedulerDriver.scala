package mesosphere.marathon

import org.apache.mesos.Protos.{ FrameworkID, FrameworkInfo, Credential }
import org.apache.mesos.{ SchedulerDriver, MesosSchedulerDriver }

import com.google.protobuf.ByteString;
import java.nio.file.{ Files, Paths };

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

    val credential: Option[Credential] = (config.mesosAuthPrincipal.get, config.mesosAuthSecret.get) match {
      case (Some(principal), Some(secret)) => {
        Option(Credential.newBuilder()
          .setPrincipal(config.mesosAuthPrincipal())
          .setSecret(ByteString.copyFrom(Files.readAllBytes(Paths.get(config.mesosAuthSecret()))))
          .build()
        )
      }
      case (Some(principal), None) => {
        Option(Credential.newBuilder()
          .setPrincipal(config.mesosAuthPrincipal())
          .build()
        )
      }

      case _ => None
    }

    val newDriver: MesosSchedulerDriver = credential match {
      case Some(cred) => new MesosSchedulerDriver(
        newScheduler,
        builder.build(),
        config.mesosMaster(),
        cred
      )
      case None => new MesosSchedulerDriver(
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
