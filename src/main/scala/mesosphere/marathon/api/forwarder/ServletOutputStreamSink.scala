package mesosphere.marathon
package api.forwarder

import akka.stream.scaladsl.{Flow, Keep, Sink}
import akka.stream.stage.{GraphStageLogic, InHandler}
import akka.stream.{Attributes, Inlet}
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.Done
import akka.stream.SinkShape
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import java.util.concurrent.atomic.AtomicBoolean
import javax.servlet.{ServletOutputStream, WriteListener}
import scala.concurrent.{Future, Promise}

/**
  * Graph stage which implements a non-blocking io ServletOutupStream writer, following the protocol outlined here:
  *
  * http://www.oracle.com/webfolder/technetwork/tutorials/obe/java/HTML5andServlet31/HTML5andServlet%203.1.html#section3
  *
  * The following runtime restrictions apply:
  *
  * - This Sink cannot be materialized twice
  * - No other writers for this outputStream may exist (IE no other component may register a writeListener)
  * - The associated context must be put in to async mode, first, by calling httpServletRequest.startAsync()
  *
  * Sink will flush the ServletOutputStream if it receives a ByteString.empty
  *
  * Materialized Future will fail if httpServletRequest.startAsync() is not called beforehand, or if a writeListener is
  * already registered for the provided ServletOutputStream.
  *
  * The outputStream is NOT closed when this sink completes; nor is the associated AsyncContext automatically closed; it
  * is the responsible module using this sink to call asyncContext.complete() AFTER the materialized future is completed
  * (failure or success).
  */
class ServletOutputStreamSink(outputStream: ServletOutputStream) extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[Done]] with StrictLogging {

  private val started = new AtomicBoolean(false)
  private val in: Inlet[ByteString] = Inlet("ServletOutputStreamSink")
  override val shape: SinkShape[ByteString] = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val promise = Promise[Done]
    val logic = new GraphStageLogic(shape) {
      val writePossible = createAsyncCallback[Unit] { _ =>
        pull(in)
      }

      val writerFailed = createAsyncCallback[Throwable] { ex =>
        doFail(ex)
      }

      override def preStart(): Unit =
        if (started.compareAndSet(false, true)) {
          try {
            outputStream.setWriteListener(new WriteListener {
              override def onWritePossible(): Unit = {
                writePossible.invoke(())
              }

              override def onError(t: Throwable): Unit = {
                writerFailed.invoke(t)
              }
            })
          } catch {
            case ex: Throwable =>
              doFail(ex)
          }
        } else {
          doFail(new IllegalStateException("This sink can only be materialized once."))
        }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val contents = grab(in)
          if (contents.isEmpty)
            outputStream.flush()
          else
            outputStream.write(contents.toArray)
          if (outputStream.isReady())
            pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          doComplete()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          doFail(ex)
        }
      })

      private def doComplete(): Unit = {
        promise.success(Done)
        completeStage()
      }

      private def doFail(ex: Throwable): Unit = {
        failStage(ex)
        promise.failure(ex)
      }
    }

    (logic, promise.future)
  }
}

object ServletOutputStreamSink {
  /**
    * Given a jax ServletOutputStream, return a Sink which writes to the Servlet request's output stream asynchronously
    *
    * See the constructor documentation for [[ServletOutputStreamSink]]
    *
    * @param outputStream the ServletOutputStream to which the sink will write
    * @param autoFlushing Whether or not to call ".flush" between each ByteString of data received.
    */
  def apply(outputStream: ServletOutputStream, autoFlushing: Boolean = false): Sink[ByteString, Future[Done]] = {
    val sink = Sink.fromGraph(new ServletOutputStreamSink(outputStream))
    if (autoFlushing)
      Flow[ByteString].intersperse(ByteString.empty).toMat(sink)(Keep.right)
    else
      sink
  }
}
