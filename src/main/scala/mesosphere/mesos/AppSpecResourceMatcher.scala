package mesosphere.mesos

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.state.{ AppDefinition, RunSpec, Container }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.tasks.PortsMatcher
import org.apache.mesos.Protos.Offer

import scala.collection.immutable.Seq

object AppSpecResourceMatcher extends StrictLogging {
  import ResourceMatcher.{ ResourceSelector, ResourceRequests }

  def matchResources(offer: Offer, runSpec: RunSpec, runningInstances: => Seq[Instance],
    selector: ResourceSelector): ResourceMatchResponse = {

    lazy val instances = runningInstances.filter { inst =>
      inst.isLaunched && inst.runSpecVersion >= runSpec.versionInfo.lastConfigChangeVersion
    }

    val badConstraints = runSpec.constraints.filterNot { constraint =>
      Constraints.meetsConstraint(instances, offer, constraint)
    }

    if (badConstraints.nonEmpty) {
      logger.info(
        s"Offer [${offer.getId.getValue}]. Constraints for run spec [${runSpec.id}] not satisfied.\n" +
          s"The conflicting constraints are: [${badConstraints.mkString(", ")}]"
      )
    }

    val portMatcher =
      runSpec match {
        case podDefinition: PodDefinition =>
          PortsMatcher(
            s"run spec [${podDefinition.id}]",
            Nil,
            Some(for {
              container <- podDefinition.containers
              endpoint <- container.endpoints
            } yield PortsMatcher.PortAsk(endpoint.hostPort)),
            false,
            selector)
        case app: AppDefinition =>
          PortsMatcher(
            s"run spec [${app.id}]",
            app.portNumbers,
            app.container.collect {
              case c: Container if c.portMappings.nonEmpty =>
                c.portMappings.map { p =>
                  PortsMatcher.PortAsk(p.hostPort)
                }
            },
            app.requirePorts,
            selector
          )

      }

    if (badConstraints.nonEmpty)
      ResourceMatchResponse.NoMatch(Seq(NoOfferMatchReason.UnfulfilledConstraint))
    else
      ResourceMatcher.matchResources(
        offer,
        ResourceRequests(
          cpus = runSpec.resources.cpus,
          mem = runSpec.resources.mem,
          gpus = runSpec.resources.gpus,
          disk = runSpec.resources.disk,
          persistentVolumes = runSpec.persistentVolumes.toList),
        selector,
        portMatcher)
  }
}
