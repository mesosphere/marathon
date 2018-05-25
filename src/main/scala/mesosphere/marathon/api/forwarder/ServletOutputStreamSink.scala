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
import scala.util.Try

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
  * While the outputStream is closed when this sink completes, the associated AsyncContext is not automatically closed;
  * it is the responsible module using this sink to call asyncContext.complete() AFTER the materialized future is
  * completed (failure or success).
  */
class ServletOutputStreamSink(outputStream: ServletOutputStream) extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[Done]] with StrictLogging {

  private val started = new AtomicBoolean(false)
  private val in: Inlet[ByteString] = Inlet("ServletOutputStreamSink")
  override val shape: SinkShape[ByteString] = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val promise = Promise[Done]
    val logic = new GraphStageLogic(shape) {
      val writePossible = createAsyncCallback[Unit] { _ =>
        logger.info("REMOVE ME - writePossible callback invoked")
        pull(in)
      }

      val writerFailed = createAsyncCallback[Throwable] { ex =>
        logger.info("REMOVE ME - writeFailed callback invoked")
        doFail(ex)
      }

      override def preStart(): Unit =
        if (started.compareAndSet(false, true)) {
          try {
            outputStream.setWriteListener(new WriteListener {
              override def onWritePossible(): Unit = {
                logger.info("REMOVE ME - onWritePossible writelistener callback")
                writePossible.invoke(())
              }

              override def onError(t: Throwable): Unit = {
                logger.info(s"REMOVE ME - onError writeListener callback: ${t}")
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

      override def postStop(): Unit = {
        logger.info("REMOVE ME - postStop called")
        Try(outputStream.close())
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val contents = grab(in)
          logger.info(s"REMOVE ME - Writing ${contents.length} bytes")
          if (contents.isEmpty)
            outputStream.flush()
          else
            outputStream.write(contents.toArray)
          if (outputStream.isReady()) {
            logger.info("REMOVE ME - isReady returned true")
            pull(in)
          } else {
            logger.info("REMOVE ME - isReady returned false")
          }
        }

        override def onUpstreamFinish(): Unit = {
          logger.info("REMOVE ME - onUpstreamFinish called; doComplete")
          doComplete()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          doFail(ex)
        }
      })

      private def doComplete(): Unit = {
        logger.info("REMOVE ME - doComplete called")
        promise.success(Done)
        completeStage()
      }

      private def doFail(ex: Throwable): Unit = {
        logger.info("REMOVE ME - doFail called")
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
