package mesosphere.marathon.core.base

import mesosphere.marathon.state.Timestamp

trait Clock {
  def now(): Timestamp
}

object Clock {
  def apply(): Clock = new DefaultClock
}

private class DefaultClock extends Clock {
  override def now(): Timestamp = Timestamp.now()
}
