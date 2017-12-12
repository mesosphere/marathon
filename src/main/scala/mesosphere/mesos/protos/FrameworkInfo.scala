package mesosphere.mesos.protos

case class FrameworkInfo(
    name: String,
    user: String = "", // Let Mesos assign the user
    id: FrameworkID = FrameworkID(""),
    failoverTimeout: Double = 0.0d,
    checkpoint: Boolean = false,
    role: String = "*") // Default role
