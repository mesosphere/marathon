package mesosphere.chaos

import java.util.concurrent.atomic.AtomicBoolean

/**
  * Represents the ServiceStatus, for now just on and off.
  */
class ServiceStatus {
  val isOn = new AtomicBoolean(true)
}
