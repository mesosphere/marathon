package mesosphere.marathon
package core.task.tracker.impl

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersStreamLogic.Tick
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerConfig}
import mesosphere.marathon.storage.repository.InstanceView

import scala.async.Async.{async, await}
import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Loads all task data into an [[InstanceTracker.InstancesBySpec]] from an [[mesosphere.marathon.storage.repository.InstanceRepository]].
  */
private[tracker] class InstancesLoaderImpl(
    repo: InstanceView,
    config: InstanceTrackerConfig)(implicit val mat: Materializer)
  extends InstancesLoader with StrictLogging {
  import scala.concurrent.ExecutionContext.Implicits.global

  override def load(): Future[InstanceTracker.InstancesBySpec] = async {

    case object Tick
    val progressReportTick = 10.seconds
    var loadedInstancesCount = 0

    // Join instances with app or pod spec.
    val coreInstances = await(
      repo.ids().mapAsync(parallelism = config.internalInstanceTrackerNumParallelLoads())(loadInstance)
        .map(Left(_))
        .merge(Source.tick(progressReportTick, progressReportTick, Right(Tick)), eagerComplete = true)
        .map {
          case Left(maybeInstance) => maybeInstance
          case Right(Tick) =>
            logger.info(s"Still loading instances. loadedInstances=$loadedInstancesCount")
            None // Ignore tick.
        }
        .collect{ case Some(x) => loadedInstancesCount += 1; x }
        .runWith(Sink.seq)
    )

    logger.info(s"Loaded ${coreInstances.size} instances")
    InstanceTracker.InstancesBySpec.forInstances(coreInstances)
  }

  def expungeOrphanedInstance(instanceId: Instance.Id): Future[Done] = async {
    logger.warn(s"No run spec ${instanceId.runSpecId} with any version was found for instance ${instanceId}. Expunging.")
    await(repo.delete(instanceId))
    Done
  }

  def loadInstance(instanceId: Instance.Id): Future[Option[Instance]] = {
    async {
      await(repo.get(instanceId)) match {
        case None =>
          await(expungeOrphanedInstance(instanceId)); None
        case Some(coreInstance) => Some(coreInstance)
      }
    }
  }
}
