package mesosphere.marathon
package api

import javax.ws.rs.core.Application
import javax.ws.rs.ext.ExceptionMapper
import scala.collection.JavaConverters._

/**
  * Simple JAX Application containing instances of controllers, exception mappers, and other things.
  *
  * @param exceptionMappers Seq of JAX exeption mappers, which transform uncaught exceptions thrown by the resources in to responses.
  * @param resources Seq of JAX resources (AKA controllers)
  */
class RootApplication(exceptionMappers: Seq[ExceptionMapper[_ <: Throwable]], resources: Seq[JaxResource]) extends Application {
  override def getSingletons(): java.util.Set[Object] = {
    val singletons = new java.util.HashSet[Object]
    singletons.addAll(resources.asJava)
    singletons.addAll(exceptionMappers.asJava)
    singletons
  }
}
