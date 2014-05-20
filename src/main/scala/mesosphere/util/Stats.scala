package mesosphere.util

import scala.concurrent.{ExecutionContext, Future}
import com.codahale.metrics.{Metric, MetricRegistry}
import java.util.concurrent.TimeUnit
import javax.inject.Inject


class Stats @Inject()(registry: MetricRegistry) {

  def time[T](name: String)(f: => T): T = {
    val tic = System.nanoTime()
    val result = f
    registry.timer(name).update(System.nanoTime() - tic, TimeUnit.NANOSECONDS)
    result
  }

  def timeFuture[T](name: String)(f: => Future[T])
                   (implicit ex: ExecutionContext): Future[T] = {
    val tic = System.nanoTime()
    f.map { result =>
      registry.timer(name).update(System.nanoTime() - tic, TimeUnit.NANOSECONDS)
      result
    }
  }

  def register(name: String, metric: Metric) = {
    registry.register(name, metric)
  }
}
