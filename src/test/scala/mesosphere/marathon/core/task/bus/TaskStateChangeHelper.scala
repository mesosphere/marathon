package mesosphere.marathon.core.task.bus

import mesosphere.marathon.core.launchqueue.impl.ActorTaskTrackerUpdateSubscriber.HandleTaskStateChange
import mesosphere.marathon.core.task.{ Task, TaskStateChange }

class TaskStateChangeHelper(val wrapped: HandleTaskStateChange)

object TaskStateChangeHelper {
  def apply(stateChange: TaskStateChange): TaskStateChangeHelper =
    new TaskStateChangeHelper(HandleTaskStateChange(stateChange))

  def expunge(taskId: Task.Id) = TaskStateChangeHelper(
    TaskStateChange.Expunge(taskId)
  )

  def failure = TaskStateChangeHelper(
    TaskStateChange.Failure("Some failure occurred!")
  )

  def noChange(taskId: Task.Id) = TaskStateChangeHelper(
    TaskStateChange.NoChange(taskId)
  )

  def update(task: Task) = TaskStateChangeHelper(
    TaskStateChange.Update(task)
  )

}