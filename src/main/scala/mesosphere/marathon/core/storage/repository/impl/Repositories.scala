package mesosphere.marathon.core.storage.repository.impl

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import mesosphere.marathon.core.storage.repository.TaskFailureRepository
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.TaskFailure
// scalastyle:off
import mesosphere.marathon.core.storage.repository.{ AppRepository, DeploymentRepository, Repository, TaskRepository, VersionedRepository }
// scalastyle:on
import mesosphere.marathon.core.storage.{ IdResolver, PersistenceStore }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.upgrade.DeploymentPlan

class AppRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
  ir: IdResolver[PathId, AppDefinition, C, K],
  marhaller: Marshaller[AppDefinition, S],
  unmarshaller: Unmarshaller[S, AppDefinition])
    extends VersionedRepository[PathId, AppDefinition, K, C, S](persistenceStore) with AppRepository

class TaskRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
  ir: IdResolver[Task.Id, Task, C, K],
  marshaller: Marshaller[Task, S],
  unmarshaller: Unmarshaller[S, Task])
    extends Repository[Task.Id, Task, K, C, S](persistenceStore) with TaskRepository

class DeploymentRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(implicit
  ir: IdResolver[String, DeploymentPlan, C, K],
  marshaller: Marshaller[DeploymentPlan, S],
  unmarshaller: Unmarshaller[S, DeploymentPlan])
    extends Repository[String, DeploymentPlan, K, C, S](persistenceStore) with DeploymentRepository

class TaskFailureRepositoryImpl[K, C, S](persistenceStore: PersistenceStore[K, C, S])(
  implicit
  ir: IdResolver[PathId, TaskFailure, C, K],
  marshaller: Marshaller[TaskFailure, S],
  unmarshaller: Unmarshaller[S, TaskFailure]
) extends VersionedRepository[PathId, TaskFailure, K, C, S](persistenceStore) with TaskFailureRepository
