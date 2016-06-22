package mesosphere.marathon.core.base

import akka.actor.{ ActorRefFactory, ActorSystem }
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Contains basic dependencies used throughout the application disregarding the concrete function.
  */
class ActorsModule(shutdownHooks: ShutdownHooks, actorSystem: ActorSystem = ActorSystem()) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def actorRefFactory: ActorRefFactory = actorSystem

  shutdownHooks.onShutdown {
    log.info("Shutting down actor system {}", actorSystem)
    Await.result(actorSystem.terminate(), 10.seconds)
  }
}
