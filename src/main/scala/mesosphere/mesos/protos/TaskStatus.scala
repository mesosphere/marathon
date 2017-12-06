package mesosphere.mesos.protos

case class TaskStatus(
    taskId: TaskID,
    state: TaskState,
    message: String = "",
    data: Array[Byte] = Array(),
    slaveId: SlaveID = SlaveID(""),
    timestamp: Double = 0)
