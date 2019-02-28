package mesosphere.mesos

import com.google.protobuf.TextFormat
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.plugin.scheduler.SchedulerPlugin
import mesosphere.marathon.state.{ AppDefinition, RunSpec }
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import org.apache.mesos.Protos.Offer

import scala.collection.immutable.Seq

object RunSpecOfferMatcher extends StrictLogging {

  /**
    * @param runSpec The runSpec for which the given offer shall be matched
    * @param offer The Mesos offer that shall be matched
    * @param knownInstances All instances associated with the given runSpec, needed to validate constraints
    * @param givenAcceptedResourceRoles The resource roles for which to look.
    */
  def matchOffer(
    runSpec: RunSpec,
    offer: Offer,
    knownInstances: => Seq[Instance],
    givenAcceptedResourceRoles: Set[String],
    conf: MarathonConf,
    schedulerPlugins: Seq[SchedulerPlugin]): ResourceMatchResponse = {
    val acceptedResourceRoles: Set[String] = {
      val roles = if (runSpec.acceptedResourceRoles.isEmpty) {
        givenAcceptedResourceRoles
      } else {
        runSpec.acceptedResourceRoles
      }
      logger.debug(s"acceptedResourceRoles $roles")
      roles
    }

    val resourceMatchResponse =
      ResourceMatcher.matchResources(offer, runSpec, knownInstances, ResourceSelector.any(acceptedResourceRoles), conf, schedulerPlugins)

    def logInsufficientResources(): Unit = {
      val runSpecHostPorts = runSpec match {
        case app: AppDefinition => if (app.requirePorts) app.portNumbers else app.portNumbers.map(_ => 0)
        case pod: PodDefinition => pod.containers.flatMap(container => container.endpoints.flatMap(_.hostPort))
      }
      val hostPorts = runSpec.container.withFilter(_.portMappings.nonEmpty).map(_.hostPorts).getOrElse(runSpecHostPorts.map(Some(_)))
      val staticHostPorts = hostPorts.filter(!_.contains(0))
      val numberDynamicHostPorts = hostPorts.count(!_.contains(0))

      val maybeStatic: Option[String] = if (staticHostPorts.nonEmpty) {
        Some(s"[${staticHostPorts.mkString(", ")}] required")
      } else {
        None
      }

      val maybeDynamic: Option[String] = if (numberDynamicHostPorts > 0) {
        Some(s"$numberDynamicHostPorts dynamic")
      } else {
        None
      }

      val portStrings = Seq(maybeStatic, maybeDynamic).flatten.mkString(" + ")

      val portsString = s"ports=($portStrings)"

      logger.debug(
        s"Offer [${offer.getId.getValue}]. Insufficient resources for [${runSpec.id}] " +
          s"(need cpus=${runSpec.resources.cpus}, mem=${runSpec.resources.mem}, disk=${runSpec.resources.disk}, " +
          s"gpus=${runSpec.resources.gpus}, $portsString, available in offer: " +
          s"[${TextFormat.shortDebugString(offer)}]"
      )
    }

    resourceMatchResponse match {
      case matches: ResourceMatchResponse.Match =>
        logger.debug(s"Offer [${offer.getId.getValue}] matches resources for [${runSpec.id}].")
        matches
      case matchesNot: ResourceMatchResponse.NoMatch =>
        logInsufficientResources()
        matchesNot
    }
  }
}
