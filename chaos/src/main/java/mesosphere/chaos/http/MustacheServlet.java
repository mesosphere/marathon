package mesosphere.chaos.http;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheException;
import com.github.mustachejava.MustacheFactory;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * A base class for servlets that render using the Mustache template templating system.  Subclasses
 * can call one of the {@link #writeTemplate} methods to render their content with the associated
 * template.
 *
 * TODO port to Scala
 */
public abstract class MustacheServlet extends HttpServlet {
  private static final String CONTENT_TYPE_TEXT_HTML = "text/html";

  private static final Logger LOG = LoggerFactory.getLogger(MustacheServlet.class.getName());

  private final MustacheFactory mf;
  private final String templateName;

  /**
   * Creates a new MustacheServlet that expects to find its template located in the same
   * package on the classpath at '{@code templateName}.mustache'.
   *
   * @param templateName The name of the string template to use.
   */
  protected MustacheServlet(String templateName) {
    this.mf = new DefaultMustacheFactory();
    this.templateName = templateName;
  }

  protected final void writeTemplate(
      HttpServletResponse response,
      Object parameters) throws IOException {

    writeTemplate(response, CONTENT_TYPE_TEXT_HTML, HttpServletResponse.SC_OK, parameters);
  }

  protected final void writeTemplate(
      HttpServletResponse response,
      String contentType,
      int status,
      Object parameters) throws IOException {

    Preconditions.checkNotNull(response);
    Preconditions.checkNotNull(contentType);
    Preconditions.checkArgument(status > 0);
    Preconditions.checkNotNull(parameters);

    try {
      InputStream stream = getClass().getResourceAsStream(templateName);
      if (stream == null) {
        throw new MustacheException("Template not found: " + templateName);
      }
      Mustache mustache = mf.compile(new InputStreamReader(stream), templateName);
      mustache.execute(response.getWriter(), parameters);

      response.setStatus(status);
      response.setContentType(contentType);
    } catch (MustacheException e) {
      LOG.error("Template exception.", e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }
}
