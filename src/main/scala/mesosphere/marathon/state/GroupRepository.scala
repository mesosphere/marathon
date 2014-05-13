package mesosphere.marathon.state

import mesosphere.marathon.api.v2.Group
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

//TODO: since most methods are copied form AppRepository, factor out a separate trait
class GroupRepository(store: PersistenceStore[Group], appRepo:AppRepository) {

  private val ID_DELIMITER = ":"

  def currentVersion(id: String): Future[Option[Group]] = fetch(id)

  def group(id: String) : Future[Option[Group]] = fetch(id)

  def group(id: String, version: Timestamp) : Future[Option[Group]] = fetch(id + ID_DELIMITER + version.toString)

  //fetch group, while fetching latest app definitions from app repository
  private def fetch(key:String) : Future[Option[Group]] = {
    this.store.fetch(key).flatMap {
      case Some(group) =>
        Future.sequence(group.apps.map(app=>appRepo.currentVersion(app.id))).map { apps =>
          Some(group.copy(apps = apps.flatten))
        }
      case None => Future.successful(None:Option[Group])
    }
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

  def listVersions(id: String): Future[Iterable[Timestamp]] = {
    val appPrefix = id + ID_DELIMITER
    this.store.names().map { names =>
      names.collect {
        case name: String if name.startsWith(appPrefix) => Timestamp(name.substring(appPrefix.length))
      }.toSeq
    }
  }

  def expunge(id: String): Future[Iterable[Boolean]] =
    listVersions(id).flatMap { timestamps =>
      val versionsDeleteResult = timestamps.map { timestamp =>
        val key = id + ID_DELIMITER + timestamp.toString
        store.expunge(key)
      }
      val currentDeleteResult = store.expunge(id)
      Future.sequence(currentDeleteResult +: versionsDeleteResult.toSeq)
    }
}
