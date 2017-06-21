package mesosphere.marathon
package core.base

import akka.actor.ActorRefFactory
import akka.stream.ActorMaterializer
import org.slf4j.LoggerFactory

/**
  * Contains basic dependencies used throughout the application disregarding the concrete function.
  */
class ActorsModule(val actorRefFactory: ActorRefFactory) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  val materializer = ActorMaterializer()(actorRefFactory)
}
