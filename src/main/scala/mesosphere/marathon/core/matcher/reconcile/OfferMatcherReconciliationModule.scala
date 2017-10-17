package mesosphere.marathon
package core.matcher.reconcile

import java.time.Clock

import akka.event.EventStream
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.reconcile.impl.{ OfferMatcherReconciler, OffersWantedForReconciliationActor }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.GroupRepository
import rx.lang.scala.subjects.BehaviorSubject
import rx.lang.scala.{ Observable, Observer, Subject }

class OfferMatcherReconciliationModule(
    reviveOffersConfig: ReviveOffersConfig,
    clock: Clock,
    marathonEventStream: EventStream,
    instanceTracker: InstanceTracker,
    groupRepository: GroupRepository,
    leadershipModule: LeadershipModule) {

  /** An offer matcher that performs reconciliation on the expected reservations. */
  lazy val offerMatcherReconciler: OfferMatcher = new OfferMatcherReconciler(instanceTracker, groupRepository)
  /** Emits true when offers are wanted for reconciliation. */
  def offersWantedObservable: Observable[Boolean] = offersWantedSubject
  /** Starts underlying actors etc. */
  def start(): Unit = offersWantedForReconciliationActor

  private[this] lazy val offersWantedSubject: Subject[Boolean] = BehaviorSubject(false)
  private[this] def offersWantedObserver: Observer[Boolean] = offersWantedSubject

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
