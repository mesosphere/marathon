package mesosphere.marathon.state

import mesosphere.marathon.metrics.Metrics

import scala.concurrent.Future

/**
  * Stores the last TaskFailure per app id.
  */
class TaskFailureRepository(
  protected val store: EntityStore[TaskFailure],
  protected val maxVersions: Option[Int] = Some(1),
  protected val metrics: Metrics)
    extends EntityRepository[TaskFailure] {

  def store(id: PathId, value: TaskFailure): Future[TaskFailure] = super.storeByName(id.safePath, value)

  def expunge(id: PathId): Future[Iterable[Boolean]] = super.expunge(id.safePath)

  def current(id: PathId): Future[Option[TaskFailure]] = super.currentVersion(id.safePath)

}
