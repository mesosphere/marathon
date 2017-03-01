package mesosphere.marathon
package metrics

sealed trait MetricPrefix {
  val name: String
}

/**
  * Metrics relating to our API.
  */
case object ApiMetric extends MetricPrefix {
  val name = "api"
}

/**
  * Metrics relating to the application code.
  */
case object ServiceMetric extends MetricPrefix {
  val name = "service"
}
