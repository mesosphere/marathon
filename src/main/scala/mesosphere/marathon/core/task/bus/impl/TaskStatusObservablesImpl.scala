package mesosphere.marathon.core.task.bus.impl

import mesosphere.marathon.core.task.bus.TaskStatusObservables
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.state.PathId
import rx.lang.scala.{ Observable, Subscription }

private[bus] class TaskStatusObservablesImpl(eventStream: InternalTaskStatusEventStream)
    extends TaskStatusObservables {

  override def forAll: Observable[TaskStatusUpdate] = forAppId(PathId.empty)

  override def forAppId(appId: PathId): Observable[TaskStatusUpdate] = {
    Observable.create { observer =>
      eventStream.subscribe(observer, appId)
      Subscription {
        eventStream.unsubscribe(observer, appId)
      }
    }
  }
}

