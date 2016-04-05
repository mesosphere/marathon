package mesosphere.marathon.state

object PortAssignment {
  /**
    * If you change this, please also update AppDefinition.json.
    */
  val PortNamePattern = """^[a-z0-9-]+$""".r
}
