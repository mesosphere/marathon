package mesosphere.marathon
package state

case class FaultDomain(region: Region, zone: Zone)

case class Region(value: String) extends AnyVal

case class Zone(value: String) extends AnyVal
