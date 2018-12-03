package mesosphere.marathon
package core.base

import akka.Done

import scala.concurrent.{ExecutionContext, Future}

trait CrashStrategy {
  def crash(reason: CrashStrategy.Reason): Future[Done]
}

object CrashStrategy {
  sealed trait Reason {
    val code: Int
  }
  case object ZookeeperConnectionFailure extends Reason { override val code: Int = 100 }
  case object ZookeeperConnectionLoss extends Reason { override val code: Int = 101 }
  case object PluginInitializationFailure extends Reason { override val code: Int = 102 }
  case object LeadershipLoss extends Reason { override val code: Int = 103 }
  case object LeadershipEndedFaulty extends Reason { override val code: Int = 104 }
  case object LeadershipEndedGracefully extends Reason { override val code: Int = 105 }
  case object MesosSchedulerError extends Reason { override val code: Int = 106 }
  case object UncaughtException extends Reason { override val code: Int = 107 }
  case object FrameworkIdMissing extends Reason { override val code: Int = 108 }
  case object IncompatibleLibMesos extends Reason { override val code: Int = 109 }
}

case object JvmExitsCrashStrategy extends CrashStrategy {
  override def crash(reason: CrashStrategy.Reason): Future[Done] = {
    Runtime.getRuntime.asyncExit(reason.code)(ExecutionContext.Implicits.global)
  }
}
