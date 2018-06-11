package mesosphere.marathon
package core.task.tracker

import akka.Done
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOperation}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{PathId, Timestamp}
import org.apache.mesos

import scala.concurrent.{ExecutionContext, Future}

/**
  * The [[InstanceTracker]] exposes the latest known state for every instance and handles the processing of
  * [[InstanceUpdateOperation]]. These might originate from
  * * Creating an instance
  * * Updating an instance (due to a state change, a timeout, a Mesos update)
  * * Expunging an instance
  *
  *
  * FIXME: To allow introducing the new asynchronous [[InstanceTracker]] without needing to
  * refactor a lot of code at once, synchronous methods are still available but should be
  * avoided in new code.
  */
trait InstanceTracker {

  def specInstancesSync(pathId: PathId): Seq[Instance]
  def specInstances(pathId: PathId)(implicit ec: ExecutionContext): Future[Seq[Instance]]

  def instance(instanceId: Instance.Id): Future[Option[Instance]]

  def instancesBySpecSync: InstanceTracker.InstancesBySpec
  def instancesBySpec()(implicit ec: ExecutionContext): Future[InstanceTracker.InstancesBySpec]

  def countActiveSpecInstances(appId: PathId): Future[Int]

  def hasSpecInstancesSync(appId: PathId): Boolean
  def hasSpecInstances(appId: PathId)(implicit ec: ExecutionContext): Future[Boolean]

  /** Process an InstanceUpdateOperation and propagate its result. */
  def process(stateOp: InstanceUpdateOperation): Future[InstanceUpdateEffect]

  def launchEphemeral(instance: Instance): Future[Done]

  def revert(instance: Instance): Future[Done]

  def forceExpunge(instanceId: Instance.Id): Future[Done]

  def updateStatus(instance: Instance, mesosStatus: mesos.Protos.TaskStatus, updateTime: Timestamp): Future[Done]

  def reservationTimeout(instanceId: Instance.Id): Future[Done]
}

object InstanceTracker {
  /**
    * Contains all tasks grouped by app ID.
    */
  case class InstancesBySpec private (instancesMap: Map[PathId, InstanceTracker.SpecInstances]) extends StrictLogging {

    def allSpecIdsWithInstances: Set[PathId] = instancesMap.keySet

    def hasSpecInstances(appId: PathId): Boolean = instancesMap.contains(appId)

    def specInstances(pathId: PathId): Seq[Instance] = {
      instancesMap.get(pathId).map(_.instances).getOrElse(Seq.empty)
    }

    def instance(instanceId: Instance.Id): Option[Instance] = for {
      runSpec <- instancesMap.get(instanceId.runSpecId)
      instance <- runSpec.instanceMap.get(instanceId)
    } yield instance

    // TODO(PODS): the instanceTracker should not expose a def for tasks
    def task(id: Task.Id): Option[Task] = {
      val instances: Option[Instance] = instance(id.instanceId)
      instances.flatMap(_.tasksMap.get(id))
    }

    def allInstances: Seq[Instance] = instancesMap.values.flatMap(_.instances)(collection.breakOut)

    private[tracker] def updateApp(appId: PathId)(
      update: InstanceTracker.SpecInstances => InstanceTracker.SpecInstances): InstancesBySpec = {
      val updated = update(instancesMap(appId))
      if (updated.isEmpty) {
        logger.info(s"Removed app [$appId] from tracker")
        copy(instancesMap = instancesMap - appId)
      } else {
        logger.debug(s"Updated app [$appId], currently ${updated.instanceMap.size} tasks in total.")
        copy(instancesMap = instancesMap + (appId -> updated))
      }
    }
  }

  object InstancesBySpec {

    def of(specInstances: collection.immutable.Map[PathId, InstanceTracker.SpecInstances]): InstancesBySpec = {
      new InstancesBySpec(specInstances.withDefault(appId => InstanceTracker.SpecInstances()))
    }

    def forInstances(instances: Seq[Instance]): InstancesBySpec = forInstances(instances: _*)
    def forInstances(instances: Instance*): InstancesBySpec = of(
      instances
        .groupBy(_.runSpecId)
        .map {
          case (appId, appInstances) =>
            val instancesById: Map[Instance.Id, Instance] = appInstances.map(instance => instance.instanceId -> instance)(collection.breakOut)
            appId -> SpecInstances(instancesById)
        }
    )

    def empty: InstancesBySpec = of(collection.immutable.Map.empty[PathId, InstanceTracker.SpecInstances])
  }
  /**
    * Contains only the instances of a specific run spec.
    *
    * @param instanceMap The instances of the app by their id.
    */
  case class SpecInstances(instanceMap: Map[Instance.Id, Instance] = Map.empty) {

    def isEmpty: Boolean = instanceMap.isEmpty
    def contains(instanceId: Instance.Id): Boolean = instanceMap.contains(instanceId)
    def instances: Seq[Instance] = instanceMap.values.to[Seq]

    private[tracker] def withInstance(instance: Instance): SpecInstances =
      copy(instanceMap = instanceMap + (instance.instanceId -> instance))

    private[tracker] def withoutInstance(instanceId: Instance.Id): SpecInstances =
      copy(instanceMap = instanceMap - instanceId)
  }
}
