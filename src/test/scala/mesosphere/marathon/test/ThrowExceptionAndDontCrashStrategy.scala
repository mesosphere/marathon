package mesosphere.marathon
package test

import akka.Done
import mesosphere.marathon.core.base.CrashStrategy

import scala.concurrent.Future

/**
  * For testing purposes only: This crash strategy will *NOT* crash but throw an exception
  */
case object ThrowExceptionAndDontCrashStrategy extends CrashStrategy {
  override def crash(reason: CrashStrategy.Reason): Future[Done] = {
    throw new Exception(s"TESTING ONLY: Failed for reason ${reason}")
  }
}
