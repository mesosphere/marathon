package mesosphere.marathon

import java.io.FileInputStream

import com.google.protobuf.ByteString
import org.apache.mesos.Protos.{Credential, FrameworkID, FrameworkInfo}
import org.apache.mesos.{MesosSchedulerDriver, Scheduler, SchedulerDriver}
import FrameworkInfo.Capability
import com.typesafe.scalalogging.StrictLogging

object MarathonSchedulerDriver extends StrictLogging {

  def newDriver(
    config: MarathonConf,
    httpConfig: HttpConf,
    newScheduler: Scheduler,
    frameworkId: Option[FrameworkID]): SchedulerDriver = {

    logger.info(s"Create new Scheduler Driver with frameworkId: $frameworkId and scheduler $newScheduler")

    val frameworkInfoBuilder = FrameworkInfo.newBuilder()
      .setName(config.frameworkName())
      .setFailoverTimeout(config.mesosFailoverTimeout().toDouble)
      .setUser(config.mesosUser())
      .setCheckpoint(config.checkpoint())
      .setHostname(config.hostname())

    // Set the role, if provided.
    config.mesosRole.toOption.foreach(frameworkInfoBuilder.setRole)

    // Set the ID, if provided
    frameworkId.foreach(frameworkInfoBuilder.setId)

    if (config.webuiUrl.isSupplied) {
      frameworkInfoBuilder.setWebuiUrl(config.webuiUrl())
    } else if (httpConfig.sslKeystorePath.isDefined) {
      // ssl enabled, use https
      frameworkInfoBuilder.setWebuiUrl(s"https://${config.hostname()}:${httpConfig.httpsPort()}")
    } else {
      // ssl disabled, use http
      frameworkInfoBuilder.setWebuiUrl(s"http://${config.hostname()}:${httpConfig.httpPort()}")
    }

    // set the authentication principal, if provided
    config.mesosAuthenticationPrincipal.toOption.foreach(frameworkInfoBuilder.setPrincipal)

    val credential: Option[Credential] = {
      def secretFileContent = config.mesosAuthenticationSecretFile.toOption.map { secretFile =>
        ByteString.readFrom(new FileInputStream(secretFile)).toStringUtf8
      }
      def credentials = config.mesosAuthenticationPrincipal.toOption.map { principal =>
        val credentials = Credential.newBuilder().setPrincipal(principal)
        //secret is optional
        config.mesosAuthenticationSecret.toOption.orElse(secretFileContent).foreach(credentials.setSecret)
        credentials.build()
      }
      if (config.mesosAuthentication()) credentials else None
    }
    credential.foreach(c => logger.info(s"Authenticate with Mesos as ${c.getPrincipal}"))

    // Task Killing Behavior enables a dedicated task update (TASK_KILLING) from mesos before a task is killed.
    // In Marathon this task update is currently ignored.
    // It makes sense to enable this feature, to support other tools that parse the mesos state, even if
    // Marathon does not use it in the moment.
    // Mesos will implement a custom kill behavior, so this state can be used by Marathon as well.
    if (config.isFeatureSet(Features.TASK_KILLING)) {
      frameworkInfoBuilder.addCapabilities(Capability.newBuilder().setType(Capability.Type.TASK_KILLING_STATE))
      logger.info("TASK_KILLING feature enabled.")
    }

    // GPU Resources allows Marathon to get offers from Mesos agents with GPUs. For details, see MESOS-5634.
    if (config.isFeatureSet(Features.GPU_RESOURCES)) {
      frameworkInfoBuilder.addCapabilities(Capability.newBuilder().setType(Capability.Type.GPU_RESOURCES))
      logger.info("GPU_RESOURCES feature enabled.")
    }

    // Enables partition awareness in Mesos to receive TASK_UNREACHABLE status updates when a task is partitioned
    // instead of a more general TASK_LOST. See also Mesos documentation.
    // Note: This feature is available since Mesos 1.1 and Marathon 1.4 requires Mesos 1.1
    frameworkInfoBuilder.addCapabilities(Capability.newBuilder().setType(Capability.Type.PARTITION_AWARE))
    logger.info("PARTITION_AWARE feature enabled.")

    // Enables region awareness in Mesos to receive offers from other regions
    frameworkInfoBuilder.addCapabilities(Capability.newBuilder().setType(Capability.Type.REGION_AWARE))
    logger.info("REGION_AWARE feature enabled")

    val frameworkInfo = frameworkInfoBuilder.build()

    logger.debug("Start creating new driver")

    val implicitAcknowledgements = false
    val newDriver: MesosSchedulerDriver = credential match {
      case Some(cred) =>
        new MesosSchedulerDriver(newScheduler, frameworkInfo, config.mesosMaster(), implicitAcknowledgements, cred)

      case None =>
        new MesosSchedulerDriver(newScheduler, frameworkInfo, config.mesosMaster(), implicitAcknowledgements)
    }

    logger.debug("Finished creating new driver", newDriver)

    newDriver
  }
}
