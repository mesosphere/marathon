package mesosphere.marathon.upgrade

import mesosphere.marathon.state.{Timestamp, PersistenceStore}
import mesosphere.marathon.api.v2.Group
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class GroupRepository(store: PersistenceStore[Group]) {

  private val ID_DELIMITER = ":"

  def currentVersion(id: String): Future[Option[Group]] = this.store.fetch(id)

  def group(id: String, version: Timestamp) : Future[Option[Group]] = {
    val key = id + ID_DELIMITER + version.toString
    this.store.fetch(key)
  }

  def store(group: Group): Future[Option[Group]] = {
    val key = group.id + ID_DELIMITER + group.version.toString
    this.store.store(group.id, group)
    this.store.store(key, group)
  }

  def groupIds: Future[Iterable[String]] = this.store.names().map { names =>
    names.collect {
      case name: String if !name.contains(ID_DELIMITER) => name
    }.toSeq
  }

  def groups(): Future[Iterable[Group]] = groupIds.flatMap { names =>
    Future.sequence(names.map( currentVersion )).map( _.flatten )
  }
}
