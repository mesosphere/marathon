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

  override def load(): Future[InstanceTracker.InstancesBySpec] = {
    async {
      val names = await(repo.ids().runWith(Sink.seq))

      logger.info(s"About to load ${names.size} instances")

      val instances = await(Future.sequence(names.map(repo.get)).map(_.flatten))

      // Join instances with app or pod.
      val t = await(Future.sequence(instances.map { stateInstance =>
        groupRepository.runSpecVersion(stateInstance.instanceId.runSpecId, stateInstance.runSpecVersion.toOffsetDateTime).map { maybeRunSpec =>
          //TODO(karsten): Handle case when no run spec was found.
          stateInstance.toCoreInstance(maybeRunSpec.get)
        }
      }))

      logger.info(s"Loaded ${t.size} instances")
      InstanceTracker.InstancesBySpec.forInstances(t)
    }
  }
}
