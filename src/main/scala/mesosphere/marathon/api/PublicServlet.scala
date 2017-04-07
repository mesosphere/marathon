package mesosphere.marathon
package api

import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.util.resource.Resource

class PublicServlet extends DefaultServlet {

  private[this] val path = "public"
  private[this] val pathWithSlash = s"^/$path/"

  // Set resource base to serve resources from classpath available under /public/*.
  override def getInitParameter(name: String): String = name match {
    case "resourceBase" => Option(getClass.getClassLoader.getResource(path)).fold(null: String)(_.toExternalForm)
    case "dirAllowed" => "false"
    case _ => super.getInitParameter(name)
  }

  // The resource requested is /public/{path}
  // Since the resource base already is public, we strip public from the path
  @SuppressWarnings(Array("all"))
  override def getResource(pathInContext: String): Resource = {
    super.getResource(pathInContext.replaceFirst(pathWithSlash, ""))
  }
}

