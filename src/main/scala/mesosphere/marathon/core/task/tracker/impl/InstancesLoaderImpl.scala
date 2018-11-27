package mesosphere.marathon
package core.task.tracker.impl

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerConfig}
import mesosphere.marathon.state.RunSpec
import mesosphere.marathon.storage.repository.{GroupRepository, InstanceRepository}

import scala.async.Async.{async, await}
import scala.concurrent.Future

/**
  * Loads all task data into an [[InstanceTracker.InstancesBySpec]] from an [[InstanceRepository]].
  */
private[tracker] class InstancesLoaderImpl(
    repo: InstanceRepository,
    groupRepository: GroupRepository,
    config: InstanceTrackerConfig)(implicit val mat: Materializer)
  extends InstancesLoader with StrictLogging {
  import scala.concurrent.ExecutionContext.Implicits.global

  override def load(): Future[InstanceTracker.InstancesBySpec] = async {
    val instances = repo.ids().mapAsync(parallelism = config.internalInstanceTrackerNumParallelLoads())(repo.get).mapConcat(_.toList)

    // Join instances with app or pod.
    val coreInstances = await(
      instances.mapAsync(parallelism = config.internalInstanceTrackerNumParallelLoads()) { stateInstance =>
        async {
          val runSpecId = stateInstance.instanceId.runSpecId
          val runSpecVersion = stateInstance.runSpecVersion.toOffsetDateTime
          await(groupRepository.runSpecVersion(runSpecId, runSpecVersion)) match {
            case Some(runSpec) => (stateInstance, Some(runSpec))
            case None =>
              logger.warn(s"No run spec $runSpecId with version ${stateInstance.runSpecVersion} was found for instance ${stateInstance.instanceId}. Trying latest.")
              await(groupRepository.latestRunSpec(runSpecId)) match {
                case Some(runSpec) => (stateInstance, Some(runSpec))
                case None => await(expungeOrphanedInstance(stateInstance))
              }
          }
        }
      }.mapConcat { // flatMap to core.Instance.
        case (stateInstance, Some(runSpec)) =>
          List(stateInstance.toCoreInstance(runSpec))
        case (stateInstance, None) =>
          val runSpecId = stateInstance.instanceId.runSpecId
          List.empty[Instance]
      }.runWith(Sink.seq)
    )

    logger.info(s"Loaded ${coreInstances.size} instances")
    InstanceTracker.InstancesBySpec.forInstances(coreInstances)
  }

  def expungeOrphanedInstance(instance: state.Instance): Future[(state.Instance, Option[RunSpec])] = async {
    logger.warn(s"No run spec ${instance.instanceId.runSpecId} with any version was found for instance ${instance.instanceId}. Expunging.")
    await(repo.delete(instance.instanceId))
    (instance, None)
  }
}
