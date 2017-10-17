package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Keep, Sink }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.{ AppRepository, PodRepository }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

@SuppressWarnings(Array("ClassNames"))
class MigrationTo146(appRepository: AppRepository, podRepository: PodRepository)(implicit ctx: ExecutionContext, mat: Materializer) extends StrictLogging {

  def migrate(): Future[Done] = {
    import MigrationTo146.Environment

    MigrationTo146.migrateUnreachableApps(appRepository, podRepository)(Environment(sys.env), ctx, mat)
  }
}

object MigrationTo146 extends StrictLogging {
  val MigrateUnreachableStrategyEnvVar = "MIGRATION_1_4_6_UNREACHABLE_STRATEGY"

  case class Environment(vars: Map[String, String])

  private def changeUnreachableStrategyForApps(app: AppDefinition): AppDefinition = {
    app.copy(unreachableStrategy = changeUnreachableStrategy(app.unreachableStrategy))
  }

  private def changeUnreachableStrategyForPods(pod: PodDefinition): PodDefinition = {
    pod.copy(unreachableStrategy = changeUnreachableStrategy(pod.unreachableStrategy))
  }

  /**
    * When opting in to the unreachable migration step
    * 1) all app and pod definitions that had a config of `UnreachableStrategy(300 seconds, 600 seconds)` (previous default) are migrated to have `UnreachableStrategy(0 seconds, 0 seconds)`
    * 2) all app and pod definitions that had a config of `UnreachableStrategy(1 second, x seconds)` are migrated to have `UnreachableStrategy(0 seconds, x seconds)`
    * 3) all app and pod definitions that had a config of `UnreachableStrategy(1 second, 2 seconds)` are migrated to have `UnreachableStrategy(0 seconds, 0 seconds)`
    *
    * @return migrated app
    */
  private def changeUnreachableStrategy(unreachableStrategy: UnreachableStrategy): UnreachableStrategy = {
    unreachableStrategy match {
      // migrate previous `hack` to achieve fastest replacement - case 3
      case UnreachableEnabled(inactiveAfter, expungeAfter) if inactiveAfter == 1.seconds && expungeAfter == 2.seconds =>
        UnreachableEnabled(0.seconds, 0.seconds)

      // migrate previous `hack` to achieve fastest replacement, but keep unreachable task in state for a while - case 2
      case UnreachableEnabled(inactiveAfter, expungeAfter) if inactiveAfter == 1.seconds =>
        UnreachableEnabled(0.seconds, expungeAfter)

      // migrate previous default - case 1
      case UnreachableEnabled(inactiveAfter, expungeAfter) if inactiveAfter == UnreachableEnabled.DefaultInactiveAfter && expungeAfter == UnreachableEnabled.DefaultExpungeAfter =>
        UnreachableEnabled(0.seconds, 0.seconds)
    }
  }

  def migrateUnreachableApps(appRepository: AppRepository, podRepository: PodRepository)(implicit env: Environment, ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    logger.info("Starting unreachable strategy migration to 1.4.6")
    val appSink =
      Flow[AppDefinition]
        .mapAsync(Int.MaxValue)(appRepository.store)
        .toMat(Sink.ignore)(Keep.right)

    val podSink =
      Flow[PodDefinition]
        .mapAsync(Int.MaxValue)(podRepository.store)
        .toMat(Sink.ignore)(Keep.right)

    val migrateUnreachableStrategyWanted = env.vars.getOrElse(MigrationTo146.MigrateUnreachableStrategyEnvVar, "false")

    if (migrateUnreachableStrategyWanted == "true") {
      appRepository.all()
        .via(appMigrationFlow)
        .runWith(appSink)
        .andThen {
          case _ =>
            logger.info("Finished 1.4.6 migration for unreachable strategy for apps")
        }
      podRepository.all()
        .via(podMigrationFlow)
        .runWith(podSink)
        .andThen {
          case _ =>
            logger.info("Finished 1.4.6 migration for unreachable strategy for pod")
        }
    } else {
      logger.info("No unreachable strategy migration to 1.4.6 wanted")
      Future.successful(Done)
    }
  }

  val appMigrationFlow =
    Flow[AppDefinition]
      .filter(_.unreachableStrategy != UnreachableDisabled)
      .map { app =>
        logger.info(s"Migrate unreachable strategy for app: ${app.id}")
        changeUnreachableStrategyForApps(app)
      }

  val podMigrationFlow =
    Flow[PodDefinition]
      .filter(_.unreachableStrategy != UnreachableDisabled)
      .map { pod =>
        logger.info(s"Migrate unreachable strategy for pod: ${pod.id}")
        changeUnreachableStrategyForPods(pod)
      }
}
