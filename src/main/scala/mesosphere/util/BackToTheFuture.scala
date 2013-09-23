package mesosphere.util

import scala.concurrent._
import scala.Some


object BackToTheFuture {

  import ExecutionContext.Implicits.global

  implicit def FutureToFutureOption[T](f: java.util.concurrent.Future[T]): Future[Option[T]] = {
    future {
      val t = f.get
      if (t == null) {
        None
      } else {
        Some(t)
      }
    }
  }

  implicit def ValueToFuture[T](value: T): Future[T] = {
    future {
      value
    }
  }
}
