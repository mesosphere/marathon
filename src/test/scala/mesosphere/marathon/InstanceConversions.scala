package mesosphere.marathon

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task

import scala.language.implicitConversions

// TODO PODs remove this conversion
trait InstanceConversions {
  implicit def tasksToInstances(tasks: Iterable[Task]): Iterable[Instance] = tasks.map(task => Instance(task))

  implicit def taskToInstance(task: Task): Instance = Instance(task)

  implicit def taskIdToInstanceId(id: Task.Id): Instance.Id = id.instanceId

  implicit def tasksIdToInstanceIds(ids: Iterable[Task.Id]): Iterable[Instance.Id] = ids.map(id => id.instanceId)
}
