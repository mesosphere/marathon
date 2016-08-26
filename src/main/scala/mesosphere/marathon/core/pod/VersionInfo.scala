package mesosphere.marathon.core.pod

import mesosphere.marathon.state.Timestamp

case class VersionInfo(lastScalingAt: Timestamp, lastConfigChangeAt: Timestamp)