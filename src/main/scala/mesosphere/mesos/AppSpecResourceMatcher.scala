package mesosphere.mesos

import mesosphere.marathon.core.task.{ Task }
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.tasks.PortsMatcher
import org.apache.mesos.Protos.Offer
import org.slf4j.LoggerFactory

object AppSpecResourceMatcher {
  private[this] val log = LoggerFactory.getLogger(getClass)
  import ResourceMatcher.{ ResourceMatch, ResourceSelector, ResourceRequests }

  def matchResources(offer: Offer, runSpec: RunSpec, runningTasks: => Iterable[Task],
    selector: ResourceSelector): Option[ResourceMatch] = {
    lazy val tasks =
      runningTasks.filter(_.launched.exists(_.runSpecVersion >= runSpec.versionInfo.lastConfigChangeVersion))
    val badConstraints = runSpec.constraints.filterNot { constraint =>
      Constraints.meetsConstraint(tasks, offer, constraint)
    }

    if (badConstraints.nonEmpty && log.isInfoEnabled) {
      log.info(
        s"Offer [${offer.getId.getValue}]. Constraints for run spec [${runSpec.id}] not satisfied.\n" +
          s"The conflicting constraints are: [${badConstraints.mkString(", ")}]"
      )
    }

    val portMappings = for {
      c <- runSpec.container
      pms <- c.portMappings if pms.nonEmpty
    } yield pms.map { p =>
      PortsMatcher.Mapping(
        containerPort = p.containerPort,
        hostPort = p.hostPort)
    }

    val portMatcher = PortsMatcher(
      s"run spec [${runSpec.id}]",
      runSpec.portNumbers,
      portMappings,
      runSpec.requirePorts,
      selector
    )

    if (badConstraints.nonEmpty)
      None
    else
      ResourceMatcher.matchResources(
        offer,
        ResourceRequests(
          cpus = runSpec.cpus,
          mem = runSpec.mem,
          gpus = runSpec.gpus,
          disk = runSpec.disk,
          persistentVolumes = runSpec.persistentVolumes.toList),
        selector,
        portMatcher)
  }
}
