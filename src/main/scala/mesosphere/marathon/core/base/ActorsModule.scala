package mesosphere.marathon
package core.base

import akka.actor.ActorRefFactory
import akka.stream.ActorMaterializer

/**
  * Contains basic dependencies used throughout the application disregarding the concrete function.
  */
class ActorsModule(val actorRefFactory: ActorRefFactory) {

  val materializer = ActorMaterializer()(actorRefFactory)
}
