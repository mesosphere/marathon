package mesosphere.marathon.test

import akka.actor.ActorSystem
import akka.testkit.{ TestKitBase, TestKit }
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfter, Suite }
import org.slf4j.LoggerFactory

/**
  * Start an actor system for all test methods and provide akka TestKit utility methods.
  */
trait MarathonActorSupport extends Suite with TestKitBase with BeforeAndAfterAll {

  private[this] val log = LoggerFactory.getLogger(getClass)

  /** Make sure that top-level actors in tests die if they throw an exception. */
  private[this] lazy val stoppingConfigStr =
    """ akka.actor.guardian-supervisor-strategy = "akka.actor.StoppingSupervisorStrategy" """
  private[this] lazy val stoppingConfig = ConfigFactory.parseString(stoppingConfigStr)

  implicit lazy val system: ActorSystem = ActorSystem(getClass.getSimpleName, stoppingConfig)
  log.info("actor system {}: starting", system.name)

  override protected def afterAll(): Unit = {
    super.afterAll()
    log.info("actor system {}: shutting down", system.name)
    TestKit.shutdownActorSystem(system)
  }
}
