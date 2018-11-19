package mesosphere.marathon
package core.task.tracker.impl

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.StrictLogging
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

  override def load(): Future[InstanceTracker.InstancesBySpec] = {
    async {
      val names = await(repo.ids().runWith(Sink.seq))

      logger.info(s"About to load ${names.size} instances")

      val instances = await(Future.sequence(names.map(repo.get)).map(_.flatten))

      // Join instances with app or pod.
      val t = await(Future.sequence(instances.map { stateInstance =>
        val runSpecId = stateInstance.instanceId.runSpecId
        val runSpecVersion = stateInstance.runSpecVersion.toOffsetDateTime
        groupRepository.runSpecVersion(runSpecId, runSpecVersion).map {
          case Some(runSpec) =>
            Some(stateInstance.toCoreInstance(runSpec))
          case None =>
            logger.warn(s"No run spec $runSpecId with version $runSpecVersion was found for instance ${stateInstance.instanceId}.")
            //TODO(karsten): use latest runspec.
            None
        }
      })).flatten

      logger.info(s"Loaded ${t.size} instances")
      InstanceTracker.InstancesBySpec.forInstances(t)
    }
  }
}
