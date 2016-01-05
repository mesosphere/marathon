package mesosphere.marathon.test

import akka.actor.ActorSystem
import akka.testkit.{ TestKitBase, TestKit }
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfter, Suite }
import org.slf4j.LoggerFactory

/**
  * Start an actor system for all test methods and provide akka TestKit utility methods.
  */
class MarathonActorSupport extends Suite with TestKitBase with BeforeAndAfterAll {

  private[this] val log = LoggerFactory.getLogger(getClass)
  implicit lazy val system = ActorSystem(getClass.getSimpleName)
  log.info("actor system {}: starting", system.name)

  override protected def afterAll(): Unit = {
    super.afterAll()
    log.info("actor system {}: shutting down", system.name)
    TestKit.shutdownActorSystem(system)
  }
}
