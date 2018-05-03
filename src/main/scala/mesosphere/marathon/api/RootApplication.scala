package mesosphere.marathon
package api

import javax.ws.rs.core.Application
import scala.collection.JavaConverters._

/**
  * Really simple JAX Application which encompasses a list of singletons
  */
class RootApplication(resources: JaxResource*) extends Application {
  override def getSingletons(): java.util.Set[Object] = {
    val singletons = new java.util.HashSet[Object]
    singletons.addAll(resources.asJava)
    singletons
  }
}
