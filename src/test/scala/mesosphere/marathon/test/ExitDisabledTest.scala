package mesosphere.marathon.test

import java.security.Permission

import akka.actor.{ ActorSystem, Scheduler }
import mesosphere.marathon.util.{ Lock, Retry }
import org.scalatest.{ BeforeAndAfterAll, Suite }

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Mixin that will disable System.exit while the suite is running.
  */
trait ExitDisabledTest extends BeforeAndAfterAll { self: Suite =>
  private val exitsCalled = Lock(mutable.ListBuffer.empty[Int])
  private var securityManager = Option.empty[SecurityManager]
  private var previousManager = Option.empty[SecurityManager]

  override def beforeAll(): Unit = {
    val newManager = new ExitDisabledSecurityManager()
    securityManager = Some(newManager)
    previousManager = Option(System.getSecurityManager)
    System.setSecurityManager(newManager)
    // intentionally last so that we disable exit as soon as possible
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    System.setSecurityManager(previousManager.orNull)
    exitsCalled(_.clear())
    super.afterAll()
  }

  def exitCalled(desiredCode: Int)(implicit system: ActorSystem, scheduler: Scheduler): Future[Boolean] = {
    implicit val ctx = system.dispatcher
    Retry.blocking("Check for exit", 20, 1.micro, 2.seconds) {
      if (exitsCalled(_.contains(desiredCode))) {
        true
      } else {
        throw new Exception("Did not find desired exit code.")
      }
    }.recover {
      case NonFatal(_) => false
    }
  }

  private class ExitDisabledSecurityManager() extends SecurityManager {
    override def checkExit(i: Int): Unit = {
      exitsCalled(_ += i)
      throw new IllegalStateException(s"Attempted to call exit with code: $i")
    }

    override def checkPermission(permission: Permission): Unit = {
      if ("exitVM".equals(permission.getName)) {
        throw new IllegalStateException("Attempted to call exitVM")
      }
    }

    override def checkPermission(permission: Permission, o: scala.Any): Unit = {
      if ("exitVM".equals(permission.getName)) {
        throw new IllegalStateException("Attempted to call exitVM")
      }
    }
  }
}
