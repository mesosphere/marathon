package mesosphere.marathon
package test

import akka.Done
import mesosphere.marathon.core.base.CrashStrategy
import scala.concurrent.Future

class TestCrashStrategy extends CrashStrategy {
  def crashed = crashedReason.nonEmpty
  @volatile var crashedReason: Option[CrashStrategy.Reason] = None
  override def crash(reason: CrashStrategy.Reason): Future[Done] = {
    crashedReason = Some(reason)
    Future.successful(Done)
  }
}
