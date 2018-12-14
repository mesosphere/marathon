package mesosphere.mesos

import mesosphere.mesos.ResourceMatcher.ResourceMatch

import scala.collection.immutable.Seq

trait ResourceMatchResponse

object ResourceMatchResponse {

  case class Match(resourceMatch: ResourceMatch) extends ResourceMatchResponse

  case class NoMatch(reasons: Seq[NoOfferMatchReason]) extends ResourceMatchResponse

}
