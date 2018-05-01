package mesosphere.chaos.http

import com.google.inject.Injector
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import net.liftweb.markdown.ThreadLocalTransformer
import scala.collection.JavaConverters._
import scala.collection.{ SortedSet, mutable }
import scala.io.Source
import scala.language.existentials
import javax.inject.{ Named, Inject }
import java.lang.reflect.Method
import java.net.URLDecoder
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest, HttpServlet }
import javax.ws.rs._

class HelpServlet @Inject() (
    @Named("helpPathPrefix") pathPrefix: String,
    injector: Injector,
    container: GuiceContainer) extends HttpServlet {

  val basePathPattern = s"^$pathPrefix/?$$".r
  val pathPattern = s"^$pathPrefix/([A-Z]+)(/.+)".r
  lazy val pathMap = makePathMap()

  val htmlHeader =
    """
<!DOCTYPE html>
<html lang="en-us">
  <head>
    <meta charset="utf-8">
    <title>Help</title>
    <link rel="stylesheet" href="/css/chaos.css">
  </head>

  <body>
    """
  val htmlFooter =
    """
  </body>
</html>
    """

  val contentType = "Content-Type"
  val textHtml = "text/html; charset=utf-8"

  override def doGet(req: HttpServletRequest, resp: HttpServletResponse) = {
    req.getRequestURI match {
      case basePathPattern() => all(req, resp)
      case pathPattern(method, path) => handleMethod(method, path, req, resp)
      case _ => resp.setStatus(HttpServletResponse.SC_NOT_FOUND)
    }
  }

  private def all(req: HttpServletRequest, resp: HttpServletResponse) {
    resp.setStatus(HttpServletResponse.SC_OK)
    resp.addHeader(contentType, textHtml)
    val writer = resp.getWriter
    try {
      writer.print(htmlHeader)
      writer.println("<h1>Help</h1>")
      writer.println("<table><thead><tr><th>Resource</th><th>Description</th></tr></thead><tbody>")
      for (key <- pathMap.keySet.to[SortedSet]) {
        val method = pathMap(key)
        writer.println(s"""
      <tr>
        <td><a href="$pathPrefix/${key._2}${key._1}">${key._2} ${key._1}</a></td>
        <td><code>${method.getDeclaringClass.getName}#${method.getName}()</code></td>
      </tr>""")
      }
      writer.println("</tbody></table>")
      writer.print(htmlFooter)
    } finally {
      writer.close()
    }
  }

  private def handleMethod(httpMethod: String, path: String, req: HttpServletRequest, resp: HttpServletResponse) = {
    val decodedPath = URLDecoder.decode(path, Option(req.getCharacterEncoding).getOrElse("UTF8"))
    val writer = resp.getWriter
    try {
      pathMap.get((decodedPath, httpMethod)) match {
        case Some(methodHandle) => {
          val klass = methodHandle.getDeclaringClass
          val resourceName = s"${klass.getSimpleName}_${methodHandle.getName}.md"

          Option(klass.getResource(resourceName)) match {
            case Some(url) => {
              resp.setStatus(HttpServletResponse.SC_OK)
              resp.addHeader(contentType, textHtml)
              val transformer = new ThreadLocalTransformer
              val markdown = transformer(Source.fromURL(url).mkString)
              writer.print(htmlHeader)
              writer.print(markdown)
              writer.print(htmlFooter)
            }
            case None => {
              resp.setStatus(HttpServletResponse.SC_NOT_FOUND)
              writer.println(s"No documentation found. Create a file named $resourceName in the resources folder " +
                s"for class ${klass.getSimpleName} to add it.")
            }
          }
        }
        case None => {
          resp.setStatus(HttpServletResponse.SC_NOT_FOUND)
          writer.println(s"No resource defined for $httpMethod $path")
        }
      }
    } finally {
      writer.close()
    }
  }

  private def makePathMap(): Map[(String, String), Method] = {
    val pathMap = new mutable.HashMap[(String, String), Method]()

    def handleClass(pathPrefix: String, klass: Class[_]) {
      for (method <- klass.getDeclaredMethods) {
        var httpMethod: Option[String] = None
        var methodPath = ""

        for (ann <- method.getAnnotations) {
          ann match {
            case m: GET => httpMethod = Some("GET")
            case m: POST => httpMethod = Some("POST")
            case m: PUT => httpMethod = Some("PUT")
            case m: DELETE => httpMethod = Some("DELETE")
            case m: HEAD => httpMethod = Some("HEAD")
            case m: OPTIONS => httpMethod = Some("OPTIONS")
            case pathAnn: Path => methodPath = s"/${pathAnn.value}"
            case _ =>
          }
        }

        val path = Option(klass.getAnnotation(classOf[Path])) match {
          case Some(ann) => s"$pathPrefix/${ann.value}$methodPath"
          case None => s"$pathPrefix$methodPath"
        }

        if (httpMethod.isDefined) {
          pathMap((path, httpMethod.get)) = method
        } else if (methodPath.nonEmpty) {
          // Sub-resources have a Path annotation but no HTTP method
          handleClass(path, method.getReturnType)
        }
      }
    }

    for (key <- injector.getAllBindings.keySet.asScala) {
      val klass = key.getTypeLiteral.getRawType
      if (klass.isAnnotationPresent(classOf[Path])) {
        handleClass(getServletContext.getContextPath, klass)
      }
    }
    pathMap.toMap
  }
}
