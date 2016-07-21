package mesosphere.marathon

import java.io.FileInputStream

import com.google.protobuf.ByteString
import mesosphere.chaos.http.HttpConf
import org.apache.mesos.Protos.{ Credential, FrameworkInfo, FrameworkID }
import org.apache.mesos.{ MesosSchedulerDriver, Scheduler, SchedulerDriver }
import org.slf4j.LoggerFactory
import FrameworkInfo.Capability

object MarathonSchedulerDriver {
  private[this] val log = LoggerFactory.getLogger(getClass)

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off method.length
  def newDriver(
    config: MarathonConf,
    httpConfig: HttpConf,
    newScheduler: Scheduler,
    frameworkId: Option[FrameworkID]): SchedulerDriver = {

    log.info(s"Create new Scheduler Driver with frameworkId: $frameworkId and scheduler $newScheduler")

    val frameworkInfoBuilder = FrameworkInfo.newBuilder()
      .setName(config.frameworkName())
      .setFailoverTimeout(config.mesosFailoverTimeout().toDouble)
      .setUser(config.mesosUser())
      .setCheckpoint(config.checkpoint())
      .setHostname(config.hostname())

    // Set the role, if provided.
    config.mesosRole.get.foreach(frameworkInfoBuilder.setRole)

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
    config.mesosAuthenticationPrincipal.get.foreach(frameworkInfoBuilder.setPrincipal)

    val credential: Option[Credential] = {
      def secretFileContent = config.mesosAuthenticationSecretFile.get.map { secretFile =>
        ByteString.readFrom(new FileInputStream(secretFile)).toStringUtf8
      }
      def credentials = config.mesosAuthenticationPrincipal.get.map { principal =>
        val credentials = Credential.newBuilder().setPrincipal(principal)
        //secret is optional
        config.mesosAuthenticationSecret.get.orElse(secretFileContent).foreach(credentials.setSecret)
        credentials.build()
      }
      if (config.mesosAuthentication()) credentials else None
    }

    // Task Killing Behavior enables a dedicated task update (TASK_KILLING) from mesos before a task is killed.
    // In Marathon this task update is currently ignored.
    // It makes sense to enable this feature, to support other tools that parse the mesos state, even if
    // Marathon does not use it in the moment.
    // Mesos will implement a custom kill behavior, so this state can be used by Marathon as well.
    if (config.isFeatureSet(Features.TASK_KILLING))
      frameworkInfoBuilder.addCapabilities(Capability.newBuilder().setType(Capability.Type.TASK_KILLING_STATE))

    val frameworkInfo = frameworkInfoBuilder.build()

    log.debug("Start creating new driver")

    val implicitAcknowledgements = false
    val newDriver: MesosSchedulerDriver = credential match {
      case Some(cred) =>
        new MesosSchedulerDriver(newScheduler, frameworkInfo, config.mesosMaster(), implicitAcknowledgements, cred)

      case None =>
        new MesosSchedulerDriver(newScheduler, frameworkInfo, config.mesosMaster(), implicitAcknowledgements)
    }

    log.debug("Finished creating new driver", newDriver)

    newDriver
  }
}
