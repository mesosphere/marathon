package mesosphere.util

import java.util.concurrent.{ Callable, Future => JFuture, FutureTask }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

/**
  * Support for conversion between Java and Scala futures.
  * TODO merge to BackToTheFuture in mesosphere.utils
  */
trait JavaScalaFutureSupport {

  /**
    * Converts a Scala Future to a Java Future
    */
  implicit class Scala2JavaFuture[A](f: Future[A]) {

    def asJava(duration: Duration): JFuture[A] = {
      val task = new FutureTask[A](new Callable[A] {
        override def call(): A = Await.result(f, duration)
      })
      new Thread(task).run()
      task
    }
  }

}

object JavaScalaFutureSupport extends JavaScalaFutureSupport

