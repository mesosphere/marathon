package mesosphere.marathon.core.task.bus.impl

import akka.event.japi.SubchannelEventBus
import akka.util.Subclassification
import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.state.PathId
import rx.lang.scala.Observer

/**
  * The internally used eventStream for [[mesosphere.marathon.core.task.bus.TaskChangeObservables]]
  */
private[bus] class InternalTaskChangeEventStream
    extends SubchannelEventBus[InstanceChange, Observer[InstanceChange], PathId] {

  override val subclassification: Subclassification[PathId] = new Subclassification[PathId] {
    override def isEqual(x: PathId, y: PathId): Boolean = x == y
    // Simplistic implementation (since we do not need anything fancy):
    // Only allow for subscription of specific appId or all appIds by
    // using the root path.
    override def isSubclass(x: PathId, y: PathId): Boolean = y.isRoot || x == y
  }

  override protected def publish(event: InstanceChange, subscriber: Observer[InstanceChange]): Unit =
    subscriber.onNext(event)
  override protected def classify(event: InstanceChange): PathId = event.runSpecId
}
