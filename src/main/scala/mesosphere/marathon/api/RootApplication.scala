package mesosphere.marathon
package api

import javax.ws.rs.core.Application
import javax.ws.rs.ext.ExceptionMapper
import scala.collection.JavaConverters._

/**
  * Really simple JAX Application which encompasses a list of singletons
  */
class RootApplication(exceptionMappers: Seq[ExceptionMapper[_ <: Throwable]], resources: Seq[JaxResource]) extends Application {
  override def getSingletons(): java.util.Set[Object] = {
    val singletons = new java.util.HashSet[Object]
    singletons.addAll(resources.asJava)
    singletons.addAll(exceptionMappers.asJava)
    singletons
  }
}
