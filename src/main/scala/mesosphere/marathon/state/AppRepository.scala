package mesosphere.marathon.state

import mesosphere.marathon.Protos
import mesosphere.marathon.api.v1.AppDefinition
import scala.collection.JavaConverters._
import scala.concurrent.Future;
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, SECONDS}

case class AppRepository(
  store: PersistenceStore[AppDefinition]
) {
  
  val ID_DELIMITER = ":"
  val defaultWait = store match { 
    case m: MarathonStore[_] => m.defaultWait
    case _ => Duration(3, SECONDS)
  }
 
  def currentVersion(appId : String) : Future[Option[AppDefinition]] = {
    this.store.fetch(appId)
  }
  
  def app(appId : String, version : Timestamp) : Future[Option[AppDefinition]] = {
    val key = appId + ID_DELIMITER + version.toString()
    this.store.fetch(key)
  }

  def store(appDef : AppDefinition) : Future[Option[AppDefinition]] = {
    val key = appDef.id + ID_DELIMITER + appDef.version.toString

    this.store.store(appDef.id, appDef)
    this.store.store(key, appDef)
  }

  def appIds() : Future[Iterable[String]] = {
    this.store.names.map { names =>
      names.collect {
        case name: String if !name.contains(ID_DELIMITER) => name
      }.toSeq
    }
  }
  
  def apps(): Future[Iterable[AppDefinition]] = {
    appIds().flatMap { names => 
      Future.sequence(names.map { name =>
      	currentVersion(name)
      }).map { _.flatten }
    }
  }
  
  def listVersions(appId : String) : Future[Iterable[Timestamp]] = {
    val appPrefix = appId + ID_DELIMITER
	this.store.names.map { names =>
	  names.collect {
	    case name: String if name.startsWith(appPrefix) => Timestamp(name.substring(appPrefix.length()))
	  }.toSeq
	}
  }
  
  def expunge(appId: String) : Future[Iterable[Boolean]] = {
    listVersions(appId).flatMap { timestamps => 
        val futureBooleans = timestamps.map { timestamp =>
        	val key = appId + ID_DELIMITER + timestamp.toString
        	store.expunge(key)
        }
        Future.sequence(futureBooleans)
    }
  }
}

object AppRepository {
  def apply(): AppRepository = AppRepository()

  def apply(apps: AppDefinition*): AppRepository = AppRepository()
}