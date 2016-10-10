package mesosphere.marathon
package state

case class Resources(
  cpus: Double = 0.0,
  mem: Double = 0.0,
  disk: Double = 0.0,
  gpus: Int = 0
)
