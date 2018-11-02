package mesosphere.marathon
package test

import com.typesafe.scalalogging.StrictLogging
import java.lang.{Exception => JavaException}
import java.util.concurrent.TimeUnit
import javax.ws.rs.container.AsyncResponse
import javax.ws.rs.core.Response
import mesosphere.marathon.api.{MarathonExceptionMapper, RejectionException}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.exceptions.TestFailedException
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Try, Success, Failure}

trait JerseyTest extends ScalaFutures with StrictLogging {
  def mapException(e: JavaException): Response = {
    val exceptionMapper = new MarathonExceptionMapper()
    exceptionMapper.toResponse(e)
  }

  /**
    * Call a synchronous Jersey controller method. Maps any thrown exceptions to responses using MarathonExceptionMapper
    */
  def syncRequest(fn: => Response): Response = {
    try fn
    catch {
      case e: JavaException => mapException(e)
    }
  }

  /**
    * Instantiate an AsyncResponse for calling an asynchronous Jersey controller method. Maps any thrown exceptions /
    * asyncResponse failures to responses using MarathonExceptionMapper.
    */
  def asyncRequest(fn: AsyncResponse => Unit)(implicit ec: ExecutionContext): Response = {
    val ar = new JerseyTest.TestAsyncResponse()
    Try(fn(ar)).
      flatMap { _ =>
        ar.response.transform({ t => Success(t) }).futureValue
      } match {
        case Success(r) => r
        case Failure(e: RejectionException) => mapException(e)
        case Failure(e: JavaException) =>
          logger.error(s"Exception while processing request", e)
          mapException(e)
        case Failure(e) => throw new TestFailedException(e, 1)
      }
  }
}

object JerseyTest {
  /**
    * Stub AsyncResource used for testing asynchronous Jersey controller methods.
    */
  class TestAsyncResponse(implicit ec: ExecutionContext) extends AsyncResponse {
    import java.util.{Map, Collection, Date}
    import javax.ws.rs.container.TimeoutHandler

    private val _result = Promise[Object]

    def response: Future[Response] = _result.future.transform {
      case Success(r: Response) => Success(r)
      case Success(o) => Failure(new RuntimeException("did not complete with a response"))
      case Failure(ex) => Failure(ex)
    }

    override def resume(response: Object): Boolean = {
      _result.success(response)
      true
    }
    override def resume(response: Throwable): Boolean = {
      _result.failure(response)
      true
    }

    override def cancel(): Boolean = ???
    override def cancel(retryAfter: Int): Boolean = ???
    override def cancel(retryAfter: Date): Boolean = ???
    override def isSuspended(): Boolean = ???
    override def isCancelled(): Boolean = ???
    override def isDone(): Boolean = _result.isCompleted
    override def setTimeout(time: Long, unit: TimeUnit): Boolean = ???
    override def setTimeoutHandler(handler: TimeoutHandler): Unit = ???
    override def register(callback: Class[_]): Collection[Class[_]] = ???
    override def register(callback: Class[_], callbacks: Class[_]*): Map[Class[_], Collection[Class[_]]] = ???
    override def register(callback: Object): Collection[Class[_]] = ???
    override def register(callback: Any, callbacks: Object*): java.util.Map[Class[_], Collection[Class[_]]] = ???
  }
}
