package mesosphere.marathon

import org.apache.mesos.Protos.{ FrameworkID, FrameworkInfo, Credential }
import org.apache.mesos.{ SchedulerDriver, MesosSchedulerDriver }

import com.google.protobuf.ByteString
import java.io.{ FileInputStream, IOException }

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
    val frameworkInfoBuilder = FrameworkInfo.newBuilder()
      .setName(frameworkName)
      .setFailoverTimeout(config.mesosFailoverTimeout().toDouble)
      .setUser(config.mesosUser())
      .setCheckpoint(config.checkpoint())

    // Set the role, if provided.
    config.mesosRole.get.foreach(frameworkInfoBuilder.setRole)

    // Set the ID, if provided
    frameworkId.foreach(frameworkInfoBuilder.setId)

    // set the authentication principal, if provided
    config.mesosAuthenticationPrincipal.get.foreach(frameworkInfoBuilder.setPrincipal)

    val credential: Option[Credential] =
      config.mesosAuthenticationPrincipal.get.map { principal =>
        val credentialBuilder = Credential.newBuilder()
          .setPrincipal(principal)

        config.mesosAuthenticationSecretFile.get.foreach { secretFile =>
          try {
            val secretBytes = ByteString.readFrom(new FileInputStream(secretFile))
            credentialBuilder.setSecret(secretBytes)
          }
          catch {
            case cause: Throwable =>
              throw new IOException(s"Error reading authentication secret from file [$secretFile]", cause)
          }
        }

        credentialBuilder.build()
      }

    val frameworkInfo = frameworkInfoBuilder.build()

    val newDriver: MesosSchedulerDriver = credential match {
      case Some(cred) =>
        new MesosSchedulerDriver(newScheduler, frameworkInfo, config.mesosMaster(), cred)

      case None =>
        new MesosSchedulerDriver(newScheduler, frameworkInfo, config.mesosMaster())
    }

    driver = Some(newDriver)
    scheduler = Some(newScheduler)
    newDriver
  }
}
