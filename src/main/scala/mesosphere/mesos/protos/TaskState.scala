package mesosphere.mesos.protos

sealed trait TaskState
case object TaskStaging extends TaskState
case object TaskStarting extends TaskState
case object TaskRunning extends TaskState
case object TaskKilling extends TaskState
case object TaskFinished extends TaskState
case object TaskFailed extends TaskState
case object TaskKilled extends TaskState
case object TaskLost extends TaskState
case object TaskError extends TaskState
