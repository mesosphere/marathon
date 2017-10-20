package mesosphere.marathon.storage.migration.legacy

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Keep, Sink }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state._
import mesosphere.marathon.storage.migration.legacy.MigrationTo_1_4_6.Environment
import mesosphere.marathon.storage.repository.InstanceRepository

import scala.concurrent.{ ExecutionContext, Future }

@SuppressWarnings(Array("ClassNames"))
class MigrationTo149(instanceRepository: InstanceRepository)(implicit ctx: ExecutionContext, mat: Materializer) extends StrictLogging {

  def migrate(): Future[Done] = {
    MigrationTo149.migrateUnreachableInstances(instanceRepository)(Environment(sys.env), ctx, mat)
  }
}

object MigrationTo149 extends StrictLogging {

  private def changeUnreachableStrategyForInstances(instance: Instance): Instance = {
    instance.copy(unreachableStrategy = MigrationTo_1_4_6.changeUnreachableStrategy(instance.unreachableStrategy))
  }

  def migrateUnreachableInstances(instanceRepository: InstanceRepository)(implicit env: Environment, ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    logger.info("Starting unreachable strategy migration to 1.4.9")

    val instanceSink =
      Flow[Instance]
        .mapAsync(Int.MaxValue)(instanceRepository.store)
        .toMat(Sink.ignore)(Keep.right)

    // we stick to already present migration indicating env variable to not confuse users
    val migrateUnreachableStrategyWanted = env.vars.getOrElse(MigrationTo_1_4_6.MigrateUnreachableStrategyEnvVar, "false")

    if (migrateUnreachableStrategyWanted == "true") {
      instanceRepository.all()
        .via(instanceMigrationFlow)
        .runWith(instanceSink)
        .andThen {
          case _ =>
            logger.info("Finished 1.4.9 migration for unreachable strategy for instances")
        }
    } else {
      logger.info("No unreachable strategy migration to 1.4.9 wanted")
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
