package mesosphere.marathon

import org.apache.mesos.Protos.{ FrameworkID, FrameworkInfo, Credential }
import org.apache.mesos.{ SchedulerDriver, MesosSchedulerDriver }

import com.google.protobuf.ByteString;
import java.io.FileInputStream;

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

    val credential: Option[Credential] =
      (config.mesosAuthenticationPrincipal.get, config.mesosAuthenticationSecretFile.get) match {
        case (Some(principal), Some(secret_file)) => {
          Option(Credential.newBuilder()
            .setPrincipal(principal)
            .setSecret(ByteString.readFrom(new FileInputStream(secret_file)))
            .build()
          )
        }
        case (Some(principal), None) => {
          Option(Credential.newBuilder()
            .setPrincipal(principal)
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
