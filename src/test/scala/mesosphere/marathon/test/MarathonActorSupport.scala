package mesosphere.marathon.test

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.testkit.{ TestKit, TestKitBase }
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfterAllConfigMap, ConfigMap }
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

/**
  * Start an actor system for all test methods and provide akka TestKit utility methods.
  */
trait MarathonActorSupport extends MarathonSpec with BeforeAndAfterAllConfigMap with TestKitBase {

  private[this] val log = LoggerFactory.getLogger(getClass)

  /** Make sure that top-level actors in tests die if they throw an exception. */
  private[this] lazy val stoppingConfigStr =
    """ akka.actor.guardian-supervisor-strategy = "akka.actor.StoppingSupervisorStrategy" """
  private[this] lazy val stoppingConfig = ConfigFactory.parseString(stoppingConfigStr)

  implicit lazy val system: ActorSystem = ActorSystem(getClass.getSimpleName, stoppingConfig)
  implicit lazy val mat: Materializer = ActorMaterializer()
  implicit lazy val ctx: ExecutionContext = ExecutionContext.global
  implicit lazy val scheduler: Scheduler = system.scheduler

  log.info("actor system {}: starting", system.name)

  override def afterAll(config: ConfigMap): Unit = {
    super.afterAll(config)
    log.info("actor system {}: shutting down", system.name)
    TestKit.shutdownActorSystem(system)
  }
}
