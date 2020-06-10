package mesosphere.marathon
package core.matcher.reconcile

import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.reconcile.impl.OfferMatcherReconciler
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.GroupRepository

class OfferMatcherReconciliationModule(instanceTracker: InstanceTracker, groupRepository: GroupRepository) {

  /** An offer matcher that performs reconciliation on the expected reservations. */
  lazy val offerMatcherReconciler: OfferMatcher = new OfferMatcherReconciler(instanceTracker, groupRepository)
}
