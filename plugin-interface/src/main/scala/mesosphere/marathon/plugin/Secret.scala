package mesosphere.marathon
package plugin

trait Secret {
  /** @return the well-known address of the secret */
  def source: String
}
