package mesosphere.marathon
package api

import java.io.ByteArrayOutputStream
import java.util
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import javax.servlet.{ ServletConfig, ServletContext, ServletOutputStream, WriteListener }

import mesosphere.UnitTest

class WebJarServletTest extends UnitTest {
  "WebJarServlet" should {
    "Get the / will send a redirect" in {
      Given("A request response mock")
      val request = req("/", "")
      val response = mock[HttpServletResponse]

      When("path / is requested")
      servlet.doGet(request, response)

      Then("A redirect response is send")
      verify(response, atLeastOnce).setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY)
      verify(response, atLeastOnce).setHeader("Location", "ui/")
    }

    "Get a directory without leading / will send a redirect" in {
      Given("A request response mock")
      val request = req("/", "some/directory")
      val response = mock[HttpServletResponse]

      When("path / is requested")
      servlet.doGet(request, response)

      Then("A redirect response is send")
      verify(response, atLeastOnce).setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY)
      verify(response, atLeastOnce).setHeader("Location", "/some/directory/")
    }

    "Get a non existing path will return 404" in {
      Given("A request response mock")
      val request = req("/", "not/existing.html")
      val response = mock[HttpServletResponse]

      When("path / is requested")
      servlet.doGet(request, response)

      Then("A redirect response is send")
      verify(response, atLeastOnce).sendError(HttpServletResponse.SC_NOT_FOUND)
    }

    "Get /ui/ will return the index.html" in {
      Given("A request response mock")
      val request = req("/ui", "/")
      val (response, out) = resp()

      When("path / is requested")
      servlet.doGet(request, response)

      Then("A redirect response is send")
      out.toString should include("<title>Marathon</title>")
      verify(response, atLeastOnce).setStatus(HttpServletResponse.SC_OK)
      verify(response, atLeastOnce).setContentType("text/html")
    }

    "Have TRACE disabled for /" in {
      Given("A request response mock")
      val request = req("/", "", "TRACE")
      val (response, _) = resp()

      When("TRACE is fired for /")
      servlet.doTrace(request, response)

      Then("TRACE is not allowed")
      verify(response, atLeastOnce).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
    }

    "Have TRACE disabled for /ui" in {
      Given("A request response mock")
      val request = req("/ui", "", "TRACE")
      val (response, _) = resp()

      When("TRACE is fired for /ui")
      servlet.doTrace(request, response)

      Then("TRACE is not allowed")
      verify(response, atLeastOnce).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
    }
  }

  def req(servletPath: String, path: String, method: String = "GET"): HttpServletRequest = {
    val request = mock[HttpServletRequest]
    request.getMethod returns method
    request.getServletPath returns servletPath
    request.getPathInfo returns path
    request.getRequestURI returns s"$servletPath$path"
    request.getServletContext returns context
    request
  }

  def resp(): (HttpServletResponse, ByteArrayOutputStream) = {
    val response = mock[HttpServletResponse]
    val outStream = new ByteArrayOutputStream()
    response.getOutputStream returns new ServletOutputStream {
      override def isReady: Boolean = ???
      override def setWriteListener(writeListener: WriteListener): Unit = ???
      override def write(b: Int): Unit = outStream.write(b)
    }
    response -> outStream
  }

  val context = {
    val context = mock[ServletContext]
    context.getMimeType("index.html") returns "text/html"
    context
  }

  lazy val servlet = {
    val servlet = new WebJarServlet
    servlet.init(new ServletConfig {
      override def getInitParameterNames: util.Enumeration[String] = ???
      override def getServletName: String = "ui"
      override def getInitParameter(name: String): String = null
      override def getServletContext: ServletContext = context
    })
    servlet
  }
}
