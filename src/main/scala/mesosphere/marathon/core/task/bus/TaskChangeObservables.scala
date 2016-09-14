package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.state.PathId
import rx.lang.scala.Observable

/**
  * Allows subscription to TaskChanged updates.
  */
trait TaskChangeObservables {
  def forAll: Observable[InstanceChange]
  def forRunSpecId(appId: PathId): Observable[InstanceChange]
}

