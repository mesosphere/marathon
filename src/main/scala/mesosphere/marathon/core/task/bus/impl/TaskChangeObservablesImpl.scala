package mesosphere.marathon.core.task.bus.impl

import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.core.task.bus.TaskChangeObservables
import mesosphere.marathon.state.PathId
import rx.lang.scala.{ Observable, Subscription }

private[bus] class TaskChangeObservablesImpl(eventStream: InternalTaskChangeEventStream)
    extends TaskChangeObservables {

  override def forAll: Observable[InstanceChange] = forRunSpecId(PathId.empty)

  override def forRunSpecId(appId: PathId): Observable[InstanceChange] = {
    Observable { observer =>
      observer.add(Subscription(eventStream.unsubscribe(observer, appId)))
      eventStream.subscribe(observer, appId)
    }
  }
}
