package mesosphere.marathon.api

import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.util.resource.Resource

class PublicServlet extends DefaultServlet {

  private[this] val path = "public"

  override def getInitParameter(name: String): String = name match {
    case "resourceBase" => getClass.getClassLoader.getResource(path).toExternalForm
    case _              => super.getInitParameter(name)
  }

  override def getResource(pathInContext: String): Resource = {
    super.getResource(pathInContext.substring(path.length + 1))
  }
}

