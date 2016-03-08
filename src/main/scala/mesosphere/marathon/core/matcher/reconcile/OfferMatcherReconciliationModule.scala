package mesosphere.marathon.core.matcher.reconcile

import akka.event.EventStream
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.flow.ReviveOffersConfig
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import mesosphere.marathon.core.matcher.reconcile.impl.{ OffersWantedForReconciliationActor, OfferMatcherReconciler }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.GroupRepository
import rx.lang.scala.{ Observable, Observer, Subject }
import rx.lang.scala.subjects.PublishSubject

class OfferMatcherReconciliationModule(
    reviveOffersConfig: ReviveOffersConfig,
    clock: Clock,
    marathonEventStream: EventStream,
    taskTracker: TaskTracker,
    groupRepository: GroupRepository,
    offerMatcherManager: OfferMatcherManager,
    leadershipModule: LeadershipModule) {

  /** An offer matcher that performs reconciliation on the expected reservations. */
  lazy val offerMatcherReconciler: OfferMatcher = new OfferMatcherReconciler(taskTracker, groupRepository)
  /** Emits true when offers are wanted for reconciliation. */
  def offersWantedObservable: Observable[Boolean] = offersWantedSubject
  /** Starts underlying actors etc. */
  def start(): Unit = offersWantedForReconciliationActor

  private[this] lazy val offersWantedSubject: Subject[Boolean] = PublishSubject()
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
