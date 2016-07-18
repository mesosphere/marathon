package mesosphere.marathon.core.storage.repository.impl

import akka.Done
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.storage.repository.{ AppRepository, Repository, TaskRepository, VersionedRepository }
import mesosphere.marathon.core.storage.{ IdResolver, PersistenceStore }
import mesosphere.marathon.state.{ AppDefinition, PathId }

import scala.concurrent.Future

class AppRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
  ir: IdResolver[PathId, AppDefinition, C, K],
  marhaller: Marshaller[AppDefinition, S],
  unmarshaller: Unmarshaller[S, AppDefinition])
    extends VersionedRepository[PathId, AppDefinition, K, C, S](persistenceStore) with AppRepository {

  override def store(appDef: AppDefinition): Future[Done] = store(appDef.id, appDef)
}

class TaskRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
  ir: IdResolver[String, MarathonTask, C, K],
  marshaller: Marshaller[MarathonTask, S],
  unmarshaller: Unmarshaller[S, MarathonTask])
    extends Repository[String, MarathonTask, K, C, S](persistenceStore) with TaskRepository {
  override def store(task: MarathonTask): Future[Done] =
    store(task.getId, task)
}
