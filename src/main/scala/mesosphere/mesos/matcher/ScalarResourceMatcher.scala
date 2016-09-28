package mesosphere.mesos.matcher

import mesosphere.mesos.protos.Resource
import mesosphere.mesos.matcher.ResourceMatcher.{ ResourceSelector, consumeResources }
import org.apache.mesos.Protos

class ScalarResourceMatcher(
    val resourceName: String, requiredValue: Double,
    selector: ResourceSelector,
    scope: ScalarMatchResult.Scope = ScalarMatchResult.Scope.NoneDisk) extends ResourceMatcher {

  def apply(offerId: String, resources: Iterable[Protos.Resource]): Iterable[MatchResult] = {

    require(scope == ScalarMatchResult.Scope.NoneDisk || resourceName == Resource.DISK)

    val matchingScalarResources = resources.filter(selector(_))
    consumeResources(requiredValue, matchingScalarResources.toList) match {
      case Left(valueLeft) =>
        Seq(NoMatch(resourceName, requiredValue, requiredValue - valueLeft, scope = scope))
      case Right((resourcesConsumed, remaining)) =>
        Seq(GeneralScalarMatch(resourceName, requiredValue, resourcesConsumed, scope = scope))
    }
  }
}
