package mesosphere.mesos.client

import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging

// TODO - move somewhere shared
trait StrictLoggingFlow extends StrictLogging {
  protected def log[T](prefix: String): Flow[T, T, NotUsed] = Flow[T].map{ e => logger.info(s"$prefix$e"); e }
}
