package mesosphere.marathon.state

import mesosphere.marathon.api.v1.AppDefinition

import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class AppRepository(store: PersistenceStore[AppDefinition]) {

  protected val ID_DELIMITER = ":"

  val defaultWait = store match { 
    case m: MarathonStore[_] => m.defaultWait
    case _ => Duration(3, SECONDS)
  }

  /**
   * Returns the most recently stored app with the supplied id.
   */
  def currentVersion(appId: String): Future[Option[AppDefinition]] =
    this.store.fetch(appId)

  /**
   * Returns the app with the supplied id and version.
   */
  def app(appId: String, version: Timestamp): Future[Option[AppDefinition]] = {
    val key = appId + ID_DELIMITER + version.toString
    this.store.fetch(key)
  }

  /**
   * Stores the supplied app, now the current version for that apps's id.
   */
  def store(appDef: AppDefinition): Future[Option[AppDefinition]] = {
    val key = appDef.id + ID_DELIMITER + appDef.version.toString
    this.store.store(appDef.id, appDef)
    this.store.store(key, appDef)
  }

  /**
   * Returns the id for all apps.
   */
  def appIds(): Future[Iterable[String]] =
    this.store.names().map { names =>
      names.collect {
        case name: String if !name.contains(ID_DELIMITER) => name
      }.toSeq
    }

  /**
   * Returns the current version for all apps.
   */
  def apps(): Future[Iterable[AppDefinition]] =
    appIds().flatMap { names => 
      Future.sequence(names.map { name =>
      	currentVersion(name)
      }).map { _.flatten }
    }

  /**
   * Returns the timestamp of each stored version of the app with the
   * supplied id.
   */
  def listVersions(appId: String): Future[Iterable[Timestamp]] = {
    val appPrefix = appId + ID_DELIMITER
  	this.store.names().map { names =>
  	  names.collect {
  	    case name: String if name.startsWith(appPrefix) =>
          Timestamp(name.substring(appPrefix.length))
  	  }.toSeq
  	}
  }

  /**
   * Deletes all versions of the app with the supplied id.
   */
  def expunge(appId: String): Future[Iterable[Boolean]] =
    listVersions(appId).flatMap { timestamps =>
      val versionsDeleteResult = timestamps.map { timestamp =>
      	val key = appId + ID_DELIMITER + timestamp.toString
      	store.expunge(key)
      }
      val currentDeleteResult = store.expunge(appId)
      Future.sequence(currentDeleteResult +: versionsDeleteResult.toSeq)
    }

}
