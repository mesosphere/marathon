package mesosphere.marathon.core.task.bus.impl

import mesosphere.marathon.core.task.bus.TaskChangeObservables
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.state.PathId
import rx.lang.scala.{ Observable, Subscription }

private[bus] class TaskChangeObservablesImpl(eventStream: InternalTaskChangeEventStream)
    extends TaskChangeObservables {

  override def forAll: Observable[TaskChanged] = forAppId(PathId.empty)

  override def forAppId(appId: PathId): Observable[TaskChanged] = {
    Observable.create { observer =>
      eventStream.subscribe(observer, appId)
      Subscription {
        eventStream.unsubscribe(observer, appId)
      }
    }
  }
}

