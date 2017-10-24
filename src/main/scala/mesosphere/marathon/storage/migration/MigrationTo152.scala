package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Keep, Sink }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state._
import mesosphere.marathon.storage.migration.MigrationTo146.Environment
import mesosphere.marathon.storage.repository.InstanceRepository

import scala.concurrent.{ ExecutionContext, Future }

@SuppressWarnings(Array("ClassNames"))
class MigrationTo152(instanceRepository: InstanceRepository)(implicit ctx: ExecutionContext, mat: Materializer) extends StrictLogging {

  def migrate(): Future[Done] = {
    MigrationTo152.migrateUnreachableInstances(instanceRepository)(Environment(sys.env), ctx, mat)
  }
}

object MigrationTo152 extends StrictLogging {

  private def changeUnreachableStrategyForInstances(instance: Instance): Instance = {
    instance.copy(unreachableStrategy = MigrationTo146.changeUnreachableStrategy(instance.unreachableStrategy))
  }

  def migrateUnreachableInstances(instanceRepository: InstanceRepository)(implicit env: Environment, ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    logger.info("Starting unreachable strategy migration to 1.5.2")

    val instanceSink =
      Flow[Instance]
        .mapAsync(Migration.maxConcurrency)(instanceRepository.store)
        .toMat(Sink.ignore)(Keep.right)

    // we stick to already present migration indicating env variable to not confuse users
    val migrateUnreachableStrategyWanted = env.vars.getOrElse(MigrationTo146.MigrateUnreachableStrategyEnvVar, "false")

    if (migrateUnreachableStrategyWanted == "true") {
      instanceRepository.all()
        .via(instanceMigrationFlow)
        .runWith(instanceSink)
        .andThen {
          case _ =>
            logger.info("Finished 1.5.2 migration for unreachable strategy for instances")
        }
    } else {
      logger.info("No unreachable strategy migration to 1.5.2 wanted")
      Future.successful(Done)
    }
  }

  val instanceMigrationFlow =
    Flow[Instance]
      .filter(_.unreachableStrategy != UnreachableDisabled)
      .map { instance =>
        logger.info(s"Migrate unreachable strategy for instance: ${instance.instanceId}")
        changeUnreachableStrategyForInstances(instance)
      }
}
