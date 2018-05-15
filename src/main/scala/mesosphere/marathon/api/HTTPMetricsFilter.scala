package mesosphere.marathon
package api

import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletRequestWrapper, HttpServletResponse, HttpServletResponseWrapper}
import mesosphere.marathon.metrics.{Metrics, ServiceMetric}

/**
  * This filter replaces the default I/O streams with proxies that count
  * the number of bytes that are sent to and from the client.
  */
class HTTPMetricsFilter extends Filter {
  private[this] val inputBytesMetric = Metrics.counter(ServiceMetric, getClass, "inputBytes")
  private[this] val outputBytesMetric = Metrics.counter(ServiceMetric, getClass, "outputBytes")

  /**
    * Wraps a `ServletOutputStream` and overrides the `write` method in
    * order to count the number of bytes that went through the stream.
    *
    * @param upstream The stream to proxy the output to
    */
  private class OutputStreamCounter(val upstream: ServletOutputStream) extends ServletOutputStream {
    var bytes: Int = 0

    override def isReady: Boolean = upstream.isReady
    override def setWriteListener(writeListener: WriteListener): Unit = upstream.setWriteListener(writeListener)
    override def write(b: Int): Unit = {
      bytes += 1
      upstream.write(b)
    }

    override def write(b: Array[Byte]): Unit = {
      bytes += b.length
      upstream.write(b)
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      bytes += len
      upstream.write(b, off, len)
    }
  }

  private class ResponseCounterWrapper(val r: HttpServletResponse) extends HttpServletResponseWrapper(r) {
    private lazy val stream = new OutputStreamCounter(super.getOutputStream)
    def bytesWritten: Int = stream.bytes

    /**
      * Override the default implementation in order to return the custom stream counter
      * @return The stream counter that wraps the base stream
      */
    override def getOutputStream: ServletOutputStream = stream
  }

  /**
    * Wraps a `ServletOutputStream` and overrides the `write` method in
    * order to count the number of bytes that went through the stream.
    *
    * @param upstream The stream to proxy the output to
    */
  private class InputStreamCounter(val upstream: ServletInputStream) extends ServletInputStream {
    var bytes: Int = 0

    override def isReady: Boolean = upstream.isReady
    override def setReadListener(readListener: ReadListener): Unit = upstream.setReadListener(readListener)
    override def isFinished: Boolean = upstream.isFinished
    override def read(): Int = {
      bytes += 1
      upstream.read()
    }

    override def read(b: Array[Byte]): Int = {
      bytes += b.length
      upstream.read(b)
    }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      bytes += len
      upstream.read(b, off, len)
    }
  }

  private class RequestCounterWrapper(val r: HttpServletRequest) extends HttpServletRequestWrapper(r) {
    private lazy val stream = new InputStreamCounter(super.getInputStream)
    def bytesRead: Int = stream.bytes

    /**
      * Override the default implementation in order to return the custom stream counter
      * @return The stream counter that wraps the base stream
      */
    override def getInputStream: ServletInputStream = stream
  }

  /**
    * This is the entry point to the filter processing
    *
    * This function wraps the request and the response with a proxy class that is going to add counters
    * to the input and output streams.
    *
    * @param request
    * @param response
    * @param chain
    */
  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    val inputCounter = new RequestCounterWrapper(request.asInstanceOf[HttpServletRequest])
    val outputCounter = new ResponseCounterWrapper(response.asInstanceOf[HttpServletResponse])

    // The proxy classes should be as fast as possible and therefore should not commit any
    // metrics to Kamon, rather simply increase the counters
    chain.doFilter(inputCounter, outputCounter)

    // Since the filter processing is synchronous, when the execution reaches this point
    // the counters should be populated. This is where we push the values to Kamon.
    inputBytesMetric.increment(inputCounter.bytesRead)
    outputBytesMetric.increment(outputCounter.bytesWritten)
  }

  override def init(filterConfig: FilterConfig): Unit = {}
  override def destroy(): Unit = {}
}
