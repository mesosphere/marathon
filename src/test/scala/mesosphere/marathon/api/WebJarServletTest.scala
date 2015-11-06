package mesosphere.marathon.api

import java.io.ByteArrayOutputStream
import java.util
import javax.servlet.{ WriteListener, ServletOutputStream, ServletConfig, ServletContext }
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.test.Mockito
import org.scalatest.{ Matchers, GivenWhenThen }

class WebJarServletTest extends MarathonSpec with Mockito with GivenWhenThen with Matchers {

  test("Get the / will send a redirect") {
    Given("A request response mock")
    val request = req("/", "")
    val response = mock[HttpServletResponse]

    When("path / is requested")
    servlet.doGet(request, response)

    Then("A redirect response is send")
    verify(response, atLeastOnce).setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY)
    verify(response, atLeastOnce).setHeader("Location", "ui/")
  }

  test("Get a directory without leading / will send a redirect") {
    Given("A request response mock")
    val request = req("/", "some/directory")
    val response = mock[HttpServletResponse]

    When("path / is requested")
    servlet.doGet(request, response)

    Then("A redirect response is send")
    verify(response, atLeastOnce).setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY)
    verify(response, atLeastOnce).setHeader("Location", "/some/directory/")
  }

  test("Get a non existing path will return 404"){
    Given("A request response mock")
    val request = req("/", "not/existing.html")
    val response = mock[HttpServletResponse]

    When("path / is requested")
    servlet.doGet(request, response)

    Then("A redirect response is send")
    verify(response, atLeastOnce).sendError(404)
  }

  test("Get /ui/ will return the index.html") {
    Given("A request response mock")
    val request = req("/ui", "/")
    val (response, out) = resp()

    When("path / is requested")
    servlet.doGet(request, response)

    Then("A redirect response is send")
    out.toString should include("<title>Marathon</title>")
    verify(response, atLeastOnce).setStatus(200)
    verify(response, atLeastOnce).setContentType("text/html")
  }

  def req(servletPath: String, path: String): HttpServletRequest = {
    val request = mock[HttpServletRequest]
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
