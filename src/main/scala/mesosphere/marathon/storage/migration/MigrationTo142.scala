package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Keep, Sink }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.AppRepository

import scala.concurrent.{ ExecutionContext, Future }

@SuppressWarnings(Array("ClassNames"))
class MigrationTo142(appRepository: AppRepository)(implicit
  ctx: ExecutionContext,
    mat: Materializer) extends StrictLogging {

  import MigrationTo142.migrationFlow
  val sink =
    Flow[AppDefinition]
      .mapAsync(Migration.maxConcurrency)(appRepository.store)
      .toMat(Sink.ignore)(Keep.right)

  def migrate(): Future[Done] = {
    logger.info("Starting migration to 1.4.2")

    appRepository.all()
      .via(migrationFlow)
      .runWith(sink)
      .andThen {
        case _ =>
          logger.info("Finished 1.4.2 migration")
      }
  }
}

object MigrationTo142 extends StrictLogging {
  private def fixResidentApp(app: AppDefinition): AppDefinition = {
    if (app.isResident)
      app.copy(unreachableStrategy = UnreachableDisabled)
    else
      app
  }

  val migrationFlow =
    Flow[AppDefinition]
      .filter(_.isResident)
      .map { app =>
        logger.info(s"Disable Unreachable strategy for resident app: ${app.id}")
        fixResidentApp(app)
      }
}
