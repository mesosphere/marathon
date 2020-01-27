package mesosphere.marathon
package api.v2

object MarathonCompatibility {

  final val V1_4 = "1.4"
  final val V1_5 = "latest" // lamentably, "latest" actually does not mean latest, but for backwards compatibility sakes we must continue to return 1.5 output for "latest"
  final val Latest = "1.10"
}
