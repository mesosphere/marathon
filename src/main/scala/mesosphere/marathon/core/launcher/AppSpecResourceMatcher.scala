package mesosphere.marathon.core.launcher

import mesosphere.marathon.core.task.{ Task, Constraints }
import mesosphere.mesos.matcher.{ ResourceMatcher, PortsMatcher, DiskResourceMatcher, ScalarResourceMatcher, ScalarMatchResult }
import mesosphere.marathon.state.RunSpec
import mesosphere.mesos.protos.Resource
import org.apache.mesos.Protos.Offer
import org.slf4j.LoggerFactory

object AppSpecResourceMatcher {
  private[this] val log = LoggerFactory.getLogger(getClass)
  import ResourceMatcher.{ ResourceMatch, ResourceSelector }

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
    else {
      // Local volumes only need to be matched if we are making a reservation for resident tasks --
      // that means if the resources that are matched are still unreserved.
      def needToReserveDisk = selector.needToReserve && runSpec.diskForPersistentVolumes > 0

      val diskMatch = if (needToReserveDisk)
        new DiskResourceMatcher(
          selector, runSpec.disk, runSpec.persistentVolumes, ScalarMatchResult.Scope.IncludingLocalVolumes)
      else
        new ScalarResourceMatcher(
          Resource.DISK, runSpec.disk, selector, ScalarMatchResult.Scope.ExcludingLocalVolumes)

      ResourceMatcher.matchResources(
        offer,
        Iterable(
          new ScalarResourceMatcher(Resource.CPUS, runSpec.cpus, selector),
          new ScalarResourceMatcher(Resource.MEM, runSpec.mem, selector),
          new ScalarResourceMatcher(Resource.GPUS, runSpec.gpus.toDouble, selector),
          diskMatch,
          portMatcher
        ),
        selector)
    }
  }
}
