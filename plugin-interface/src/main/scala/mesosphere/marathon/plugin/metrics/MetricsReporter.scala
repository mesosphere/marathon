package mesosphere.marathon.plugin.metrics

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.plugin.plugin

trait MetricsReporter {
  /**
    * stop shuts down the metrics reporter
    */
  def stop(): Unit
}

object MetricsReporter {

  /**
    * Factory creates and **starts** a reporter instance that consumes metrics from the given registry.
    */
  trait Factory extends Function1[MetricRegistry, Option[MetricsReporter]] with plugin.Plugin

  object Factory {
    def apply(f: MetricRegistry => Option[MetricsReporter]): Factory = new Factory {
      override def apply(reg: MetricRegistry): Option[MetricsReporter] = f(reg)
    }
  }
}
