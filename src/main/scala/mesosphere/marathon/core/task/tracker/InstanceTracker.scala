package mesosphere.marathon
package core.task.tracker

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId

import scala.concurrent.{ ExecutionContext, Future }

/**
  * The TaskTracker exposes the latest known state for every task.
  *
  * It is an read-only interface. For modification, see
  * * [[TaskStateOpProcessor]] for create, update, delete operations
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

  def countLaunchedSpecInstancesSync(appId: PathId): Int
  def countLaunchedSpecInstances(appId: PathId): Future[Int]

  def hasSpecInstancesSync(appId: PathId): Boolean
  def hasSpecInstances(appId: PathId)(implicit ec: ExecutionContext): Future[Boolean]
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
      new InstancesBySpec(specInstances.withDefault(appId => InstanceTracker.SpecInstances(appId)))
    }

    def of(apps: InstanceTracker.SpecInstances*): InstancesBySpec = of(Map(apps.map(app => app.specId -> app): _*))

    def forInstances(tasks: Instance*): InstancesBySpec = of(
      tasks.groupBy(_.runSpecId).map { case (appId, appTasks) => appId -> SpecInstances.forInstances(appId, appTasks.to[Seq]) }
    )

    def empty: InstancesBySpec = of(collection.immutable.Map.empty[PathId, InstanceTracker.SpecInstances])
  }
  /**
    * Contains only the tasks of the app with the given app ID.
    *
    * @param specId The id of the app.
    * @param instanceMap The tasks of this app by task ID. FIXME: change keys to Task.TaskID
    */
  case class SpecInstances(specId: PathId, instanceMap: Map[Instance.Id, Instance] = Map.empty) {

    def isEmpty: Boolean = instanceMap.isEmpty
    def contains(taskId: Instance.Id): Boolean = instanceMap.contains(taskId)
    def instances: Seq[Instance] = instanceMap.values.to[Seq]

    private[tracker] def withInstance(instance: Instance): SpecInstances =
      copy(instanceMap = instanceMap + (instance.instanceId -> instance))

    private[tracker] def withoutInstance(instanceId: Instance.Id): SpecInstances =
      copy(instanceMap = instanceMap - instanceId)
  }

  object SpecInstances {
    def forInstances(pathId: PathId, instances: Seq[Instance]): SpecInstances =
      SpecInstances(pathId, instances.map(instance => instance.instanceId -> instance)(collection.breakOut))
  }
}
