package mesosphere.marathon
package metrics.current

sealed trait UnitOfMeasurement

object UnitOfMeasurement {
  case object None extends UnitOfMeasurement

  // Memory is measure in bytes. ".bytes" is appended to metric names.
  case object Memory extends UnitOfMeasurement

  // Time is measured in seconds. ".seconds" is appended to timer names.
  case object Time extends UnitOfMeasurement
}
