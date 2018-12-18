package mesosphere.marathon
package core.instance

/**
  * Represents a reservation for all resources that are needed for launching an instance
  * and associated persistent local volumes.
  */
case class Reservation(volumeIds: Seq[LocalVolumeId], state: Reservation.State)
