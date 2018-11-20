package mesosphere.marathon
package core.task.tracker.impl

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.storage.repository.{GroupRepository, InstanceRepository}

import scala.async.Async.{async, await}
import scala.concurrent.Future

/**
  * Loads all task data into an [[InstanceTracker.InstancesBySpec]] from an [[InstanceRepository]].
  */
private[tracker] class InstancesLoaderImpl(repo: InstanceRepository, groupRepository: GroupRepository)(implicit val mat: Materializer)
  extends InstancesLoader with StrictLogging {
  import scala.concurrent.ExecutionContext.Implicits.global

  override def load(): Future[InstanceTracker.InstancesBySpec] = async {
    val instances = repo.ids().mapAsync(parallelism = 5)(repo.get).mapConcat(_.toList)

    // Join instances with app or pod.
    val t = await(
      instances.mapAsync(parallelism = 5) { stateInstance =>
        val runSpecId = stateInstance.instanceId.runSpecId
        val runSpecVersion = stateInstance.runSpecVersion.toOffsetDateTime
        groupRepository.runSpecVersion(runSpecId, runSpecVersion).map(stateInstance -> _)
      }.mapConcat {
        case (stateInstance, Some(runSpec)) =>
          List(stateInstance.toCoreInstance(runSpec))
        case (stateInstance, None) =>
          logger.warn(s"No run spec ${stateInstance.instanceId.runSpecId} with version ${stateInstance.runSpecVersion} was found for instance ${stateInstance.instanceId}.")
          //TODO(karsten): use latest runspec.
          List.empty[Instance]
      }.runWith(Sink.seq)
    )

    logger.info(s"Loaded ${t.size} instances")
    InstanceTracker.InstancesBySpec.forInstances(t)
  }
}
