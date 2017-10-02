package mesosphere.marathon
package core.election

import akka.actor.Cancellable
import akka.stream.scaladsl.{ Keep, Source }
import mesosphere.marathon.stream.EnrichedSource

object PsuedoElectionStream {

  /**
    * Stream which immediately emits that we are leader; upon cancellation, emits that we are not
    */
  def apply(): Source[LeadershipState, Cancellable] = {
    Source.single(LeadershipState.ElectedAsLeader)
      .concatMat(EnrichedSource.emptyCancellable)(Keep.right)
      .concat(Source.single(LeadershipState.Standby(None)))
  }
}
