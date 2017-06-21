package mesosphere.marathon
package test

import java.security.Permission

import akka.actor.{ ActorSystem, Scheduler }
import mesosphere.marathon.core.base.RichRuntime
import mesosphere.marathon.util.{ Lock, Retry, RichLock }
import org.scalatest.{ BeforeAndAfterAll, Suite }

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Mixin that will disable System.exit while the suite is running.
  */
trait ExitDisabledTest extends Suite with BeforeAndAfterAll {
  private var securityManager = Option.empty[SecurityManager]
  private var previousManager = Option.empty[SecurityManager]

  override def beforeAll(): Unit = {
    // intentionally initialize...
    ExitDisabledTest.exitsCalled(_.size)
    ExitDisabledTest.install()
    // intentionally last so that we disable exit as soon as possible
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    // intentionally first
    super.afterAll()
    ExitDisabledTest.remove()
  }

  def exitCalled(desiredCode: Int)(implicit system: ActorSystem, scheduler: Scheduler): Future[Boolean] = {
    implicit val ctx = system.dispatcher
    Retry.blocking("Check for exit", Int.MaxValue, 1.micro, RichRuntime.DefaultExitDelay.plus(1.second)) {
      if (ExitDisabledTest.exitsCalled(_.contains(desiredCode))) {
        ExitDisabledTest.exitsCalled(e => e.remove(e.indexOf(desiredCode)))
        true
      } else {
        throw new Exception("Did not find desired exit code.")
      }
    }.recover {
      case NonFatal(_) => false
    }
  }
}

object ExitDisabledTest {
  private val exitsCalled = Lock(mutable.ArrayBuffer.empty[Int])
  private val installLock = RichLock()
  private var installCount = 0
  private var previousManager = Option.empty[SecurityManager]

  def install(): Unit = installLock {
    if (installCount == 0) {
      val newManager = new ExitDisabledSecurityManager()
      previousManager = Option(System.getSecurityManager)
      System.setSecurityManager(newManager)
    }
    installCount += 1
  }

  def remove(): Unit = installLock {
    installCount -= 1
    if (installCount == 0) {
      System.setSecurityManager(previousManager.orNull)
    }
  }

  private class ExitDisabledSecurityManager() extends SecurityManager {
    override def checkExit(i: Int): Unit = {
      if (i != 0) {
        exitsCalled(_ += i)
        throw new IllegalStateException(s"Attempted to call exit with code: $i")
      }
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
