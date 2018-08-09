package mesosphere.marathon
package core.matcher.reconcile

import akka.actor.Cancellable
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Source}
import java.time.Clock

import akka.event.EventStream
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.reconcile.impl.{OfferMatcherReconciler, OffersWantedForReconciliationActor}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.stream.{Subject}

class OfferMatcherReconciliationModule(
    reviveOffersConfig: ReviveOffersConfig,
    clock: Clock,
    marathonEventStream: EventStream,
    instanceTracker: InstanceTracker,
    groupRepository: GroupRepository,
    leadershipModule: LeadershipModule)(implicit materializer: Materializer) {

  /** An offer matcher that performs reconciliation on the expected reservations. */
  lazy val offerMatcherReconciler: OfferMatcher = new OfferMatcherReconciler(instanceTracker, groupRepository)
  /** Emits true when offers are wanted for reconciliation. */
  def offersWantedObservable: Source[Boolean, Cancellable] = offersWantedSubject
  /** Starts underlying actors etc. */
  def start(): Unit = offersWantedForReconciliationActor

  val (offersWantedObserver, offersWantedSubject) = Source.queue[Boolean](16, OverflowStrategy.backpressure)
    .toMat(Subject[Boolean](16, OverflowStrategy.dropHead))(Keep.both)
    .run

  private[this] lazy val offersWantedForReconciliationActor = leadershipModule.startWhenLeader(
    OffersWantedForReconciliationActor.props(
      reviveOffersConfig,
      clock,
      marathonEventStream,
      offersWantedObserver
    ),
    "offersWantedForReconciliation"
  )

}
