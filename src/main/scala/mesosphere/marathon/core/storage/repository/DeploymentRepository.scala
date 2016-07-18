package mesosphere.marathon.core.storage.repository

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.core.storage.PersistenceStore
import mesosphere.marathon.core.storage.impl.memory.{ Identity, InMemoryStoreSerialization, RamId }
import mesosphere.marathon.core.storage.impl.zk.{ ZkId, ZkSerialized, ZkStoreSerialization }
import mesosphere.marathon.core.storage.repository.impl.DeploymentRepositoryImpl
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.concurrent.Future

trait DeploymentRepository {
  def ids(): Source[String, NotUsed]
  def get(id: String): Future[Option[DeploymentPlan]]
  def store(id: String, plan: DeploymentPlan): Future[Done]
  def store(plan: DeploymentPlan): Future[Done] = store(plan.id, plan)
  def delete(id: String): Future[Done]
  def all(): Source[DeploymentPlan, NotUsed]
}

object DeploymentRepository {
  def zkRepository(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]): DeploymentRepository = {
    import ZkStoreSerialization._
    new DeploymentRepositoryImpl(persistenceStore)
  }

  def inMemRepository(persistenceStore: PersistenceStore[RamId, String, Identity]): DeploymentRepository = {
    import InMemoryStoreSerialization._
    new DeploymentRepositoryImpl(persistenceStore)
  }
}
