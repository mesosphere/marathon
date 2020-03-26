package mesosphere.marathon
package core.task.tracker

import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceUpdateEffect, InstanceUpdateOperation, InstancesSnapshot}
import mesosphere.marathon.core.instance.{Goal, GoalChangeReason, Instance}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{AbsolutePathId, Timestamp}
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
trait InstanceTracker extends StrictLogging {

  /**
    * Retrieves all instances for one run spec.
    *
    * This method can have two behaviors. By default it fetches the current persisted state. This will ignore all
    * all pending updates for an instances. The behavior can be changed by passing {{{readAfterWrite = true}}}. In
    * this case the query for all instances is queued behind all pending updates and thus will see their effect.
    *
    * The {{{readAfterWrite}}} option should be used if the logic is short lived and has to see all updates before
    * proceeding. Eg the [[mesosphere.marathon.core.deployment.impl.TaskReplaceActor]] is only alive for the period
    * of a deployment. It does not *wait* for updates of unknown instances. Thus it uses the {{{readAfterWrite}}}
    * option to guarantee that all updates are processed before it receives the current app state, ie all instances,
    * and decides whether the deployment is done or not.
    *
    * @param pathId The id of the run spec.
    * @param readAfterWrite If true waits until all pending updates are written before returning instance.
    * @return A future sequence of all instances.
    */
  def specInstances(pathId: AbsolutePathId, readAfterWrite: Boolean = false)(implicit ec: ExecutionContext): Future[Seq[Instance]]

  /** Synchronous blocking version of [[InstanceTracker.specInstances()]]. */
  def specInstancesSync(pathId: AbsolutePathId, readAfterWrite: Boolean = false): Seq[Instance]

  /**
    * Look up a specific instance by id.
    *
    * @param instanceId The identifier of the instance.
    * @return None if the instance does not exist, or the instance otherwise.
    */
  def get(instanceId: Instance.Id): Future[Option[Instance]] = instance(instanceId)

  /**
    * Look up a specific instance by id.
    *
    * @param instanceId The identifier of the instance.
    * @return None if the instance does not exist, or the instance otherwise.
    */
  def instance(instanceId: Instance.Id): Future[Option[Instance]]

  def instancesBySpecSync: InstanceTracker.InstancesBySpec
  def instancesBySpec()(implicit ec: ExecutionContext): Future[InstanceTracker.InstancesBySpec]

  def countActiveSpecInstances(appId: AbsolutePathId): Future[Int]

  def hasSpecInstancesSync(appId: AbsolutePathId): Boolean
  def hasSpecInstances(appId: AbsolutePathId)(implicit ec: ExecutionContext): Future[Boolean]

  /** Process an InstanceUpdateOperation and propagate its result. */
  def process(stateOp: InstanceUpdateOperation): Future[InstanceUpdateEffect]

  def schedule(instance: Instance): Future[Done]

  def schedule(instances: Seq[Instance])(implicit ec: ExecutionContext): Future[Done] = {
    logger.info(s"Scheduling instances ${instances.mkString(",\n")}")
    Future.sequence(instances.map(schedule)).map { _ => Done }
  }

  def revert(instance: Instance): Future[Done]

  def forceExpunge(instanceId: Instance.Id): Future[Done]

  def updateStatus(instance: Instance, mesosStatus: mesos.Protos.TaskStatus, updateTime: Timestamp): Future[Done]

  def reservationTimeout(instanceId: Instance.Id): Future[Done]

  def setGoal(instanceId: Instance.Id, goal: Goal, reason: GoalChangeReason): Future[Done]

  /**
    * An ongoing source of instance updates. On materialization, receives an update for all current instances
    */
  val instanceUpdates: InstanceTracker.InstanceUpdates
}

object InstanceTracker {
  type InstanceUpdates = Source[(InstancesSnapshot, Source[InstanceChange, NotUsed]), NotUsed]
  /**
    * Contains all tasks grouped by app ID.
    */
  case class InstancesBySpec private (instancesMap: Map[AbsolutePathId, InstanceTracker.SpecInstances]) extends StrictLogging {

    def allSpecIdsWithInstances: Set[AbsolutePathId] = instancesMap.keySet

    def hasSpecInstances(appId: AbsolutePathId): Boolean = instancesMap.contains(appId)

    def specInstances(pathId: AbsolutePathId): Seq[Instance] = {
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

    def allInstances: Seq[Instance] = instancesMap.values.iterator.flatMap(_.instances).toSeq

    private[tracker] def updateApp(appId: AbsolutePathId)(
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

    def of(specInstances: collection.immutable.Map[AbsolutePathId, InstanceTracker.SpecInstances]): InstancesBySpec = {
      new InstancesBySpec(specInstances.withDefault(appId => InstanceTracker.SpecInstances()))
    }

    def forInstances(instances: Iterable[Instance]): InstancesBySpec = of(
      instances
        .groupBy(_.runSpecId)
        .map {
          case (appId, appInstances) =>
            val instancesById: Map[Instance.Id, Instance] = appInstances.iterator.map(instance => instance.instanceId -> instance).toMap
            appId -> SpecInstances(instancesById)
        }
    )

    def empty: InstancesBySpec = of(collection.immutable.Map.empty[AbsolutePathId, InstanceTracker.SpecInstances])
  }
  /**
    * Contains only the instances of a specific run spec.
    *
    * @param instanceMap The instances of the app by their id.
    */
  case class SpecInstances(instanceMap: Map[Instance.Id, Instance] = Map.empty) {

    def isEmpty: Boolean = instanceMap.isEmpty
    def contains(instanceId: Instance.Id): Boolean = instanceMap.contains(instanceId)
    def instances: Seq[Instance] = instanceMap.values.to(Seq)

    private[tracker] def withInstance(instance: Instance): SpecInstances =
      copy(instanceMap = instanceMap + (instance.instanceId -> instance))

    private[tracker] def withoutInstance(instanceId: Instance.Id): SpecInstances =
      copy(instanceMap = instanceMap - instanceId)
  }
}
