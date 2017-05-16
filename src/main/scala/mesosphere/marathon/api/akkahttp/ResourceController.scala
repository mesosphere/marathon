package mesosphere.marathon
package api.akkahttp

import java.net.URI

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.StrictLogging

/**
  * The ResourceController delivers static content from the classpath.
  */
class ResourceController extends Controller with StrictLogging {
  import Directives._

  /**
    * This route serves resources from the class path.
    * It makes sure, only resources below base are send.
    */
  private[this] def fromResource(base: String, resource: String): Route = {
    logger.info(s"Serve static resource from base $base resource: $resource")
    val effectiveResource = if (resource.isEmpty) "index.html" else resource
    val effectivePath = s"$base/$effectiveResource"
    // make sure, a request does not escape its base path via ..
    val normalized = new URI(effectivePath).normalize().getPath
    if (normalized.startsWith(base)) getFromResource(effectivePath)
    else reject
  }

  private[this] def webJarResource(jar: String)(resource: String): Route = {
    fromResource(s"META-INF/resources/webjars/$jar", resource)
  }

  private[this] def publicResource(resource: String): Route = {
    fromResource("public", resource)
  }

  override val route: Route = get {
    pathSingleSlash { redirect("ui/", StatusCodes.TemporaryRedirect) } ~
      path("ui") { redirect("ui/", StatusCodes.TemporaryRedirect) } ~
      path("help") { redirect("help/", StatusCodes.TemporaryRedirect) } ~
      path("api-console") { redirect("api-console/", StatusCodes.TemporaryRedirect) } ~
      path("public") { redirect("public/", StatusCodes.TemporaryRedirect) } ~
      path("ui" / Remaining) { webJarResource("ui") } ~
      path("help" / Remaining) { webJarResource("api-console") } ~
      path("api-console" / Remaining) { webJarResource("api-console") } ~
      path("public" / Remaining) { publicResource }
  }
}

