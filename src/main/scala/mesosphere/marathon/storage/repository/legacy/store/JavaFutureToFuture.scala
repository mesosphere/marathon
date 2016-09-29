package mesosphere.marathon.storage.repository.legacy.store

import java.util.concurrent.{ ExecutionException, TimeUnit, Future => JFuture }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, blocking }
import scala.language.{ implicitConversions, postfixOps }

object JavaFutureToFuture {

  case class Timeout(duration: Duration)

  // To use this default timeout, please "import  mesosphere.util.BackToTheFuture.Implicits._"
  object Implicits {
    implicit val defaultTimeout = Timeout(2 seconds)
  }

  implicit def futureToFuture[T](f: JFuture[T])(implicit ec: ExecutionContext, timeout: Timeout): Future[T] = {
    Future {
      blocking {
        try {
          f.get(timeout.duration.toMicros, TimeUnit.MICROSECONDS)
        } catch {
          case e: ExecutionException => throw e.getCause
        }
      }
    }
  }

}
