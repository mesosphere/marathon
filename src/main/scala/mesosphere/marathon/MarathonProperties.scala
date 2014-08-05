package mesosphere.marathon

import java.util.Properties

object MarathonProperties {
  val properties = new Properties
  properties.load(getClass.getClassLoader.getResourceAsStream("marathon.properties"))
}
