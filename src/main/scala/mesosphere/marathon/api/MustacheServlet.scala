package mesosphere.marathon
package api

import com.github.mustachejava.DefaultMustacheFactory
import com.github.mustachejava.Mustache
import com.github.mustachejava.MustacheException
import com.github.mustachejava.MustacheFactory
import com.google.common.base.Preconditions
import com.typesafe.scalalogging.StrictLogging

import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletResponse
import java.io.InputStream
import java.io.InputStreamReader

/**
  * A base class for servlets that render using the Mustache template templating system.  Subclasses
  * can call one of the {@link #writeTemplate} methods to render their content with the associated
  * template.
  *
  * The MustacheServlet expects to find its template located in the same package on the classpath at '{@code
  * templateName}.mustache'.
  *
  * Ported from Java.
  *
  * @param templateName The name of the string template to use.
  */
abstract class MustacheServlet(val templateName: String) extends HttpServlet with StrictLogging {
  private val CONTENT_TYPE_TEXT_HTML = "text/html"

  private val mf: MustacheFactory = new DefaultMustacheFactory()

  protected def writeTemplate(
    response: HttpServletResponse,
    parameters: Object): Unit = {

    writeTemplate(response, CONTENT_TYPE_TEXT_HTML, HttpServletResponse.SC_OK, parameters)
  }

  protected final def writeTemplate(
    response: HttpServletResponse,
    contentType: String,
    status: Int,
    parameters: Object): Unit = {

    Preconditions.checkNotNull(response)
    Preconditions.checkNotNull(contentType)
    Preconditions.checkArgument(status > 0)
    Preconditions.checkNotNull(parameters)

    try {
      val stream: InputStream = getClass().getResourceAsStream(templateName)
      if (stream == null) {
        throw new MustacheException(s"Template not found: ${templateName}")
      }
      val mustache: Mustache = mf.compile(new InputStreamReader(stream), templateName)

      mustache.execute(response.getWriter(), parameters)

      response.setStatus(status)
      response.setContentType(contentType)
    } catch {
      case e: MustacheException =>
        logger.error("Template exception.", e)
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
    }
  }
}
