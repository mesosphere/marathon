package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.state._

import scala.async.Async.{async, await}
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class MigrationTo19300(persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] =
    async {
      logger.info("Starting migration to 1.9.300")
      await(MigrationTo19300.migrateApps(persistenceStore))
      await(MigrationTo19300.migratePods(persistenceStore))
    }

}

object MigrationTo19300 extends MaybeStore with StrictLogging {
  val migrationVersion = StorageVersions(19, 300)
  def repairRoles(role: String, acceptedResourceRoles: Seq[String]): Seq[String] = {
    val repaired = acceptedResourceRoles.filter { acceptedResourceRole =>
      acceptedResourceRole == role || acceptedResourceRole == ResourceRole.Unreserved
    }

    if (acceptedResourceRoles.nonEmpty && repaired.isEmpty)
      Seq("*")
    else
      repaired
  }

  private[migration] val appMigratingFlow = Flow[(Protos.ServiceDefinition, Option[OffsetDateTime])].mapConcat {
    case (service, version) =>
      val acceptedResourceRoles = if (service.hasAcceptedResourceRoles) service.getAcceptedResourceRoles.getRoleList.asScala.toList else Nil
      val role = service.getRole
      val repaired = repairRoles(role, acceptedResourceRoles)
      if (repaired != acceptedResourceRoles) {
        val repairedApp = service.toBuilder.setAcceptedResourceRoles(Protos.ResourceRoles.newBuilder().addAllRole(repaired.asJava)).build
        logger.info(
          s"Culling invalid resource roles from ${service.getId}, version ${version}; old acceptedResourceRoles: ${acceptedResourceRoles
            .mkString(",")}, new acceptedResourceRoles: ${repaired.mkString(",")}"
        )
        List((repairedApp, version))
      } else {
        Nil
      }
  }

  private[migration] val podMigratingFlow = Flow[(raml.Pod, Option[OffsetDateTime])].mapConcat {
    case (podRaml, version) =>
      val repaired = for {
        scheduling <- podRaml.scheduling
        placement <- scheduling.placement

        acceptedResourceRoles: Seq[String] = placement.acceptedResourceRoles
        role = podRaml.role.getOrElse { throw new RuntimeException("Bug! Migration 19100 should have set this value") }
        repaired = repairRoles(role, acceptedResourceRoles)
        if (repaired != acceptedResourceRoles)
      } yield {
        val repairedPod =
          podRaml.copy(scheduling = Some(scheduling.copy(placement = Some(placement.copy(acceptedResourceRoles = repaired)))))
        logger.info(
          s"Culling invalid resource roles from pod ${podRaml.id}, version ${version}; old acceptedResourceRoles: ${acceptedResourceRoles
            .mkString(",")}, new acceptedResourceRoles: ${repaired.mkString(",")}"
        )
        (repairedPod, version)
      }
      repaired.toList
  }

  /**
    * Loads all app definitions from store and sets the role to Marathon's default role.
    *
    * @param persistenceStore The ZooKeeper storage.
    * @return Successful future when done.
    */
  def migrateApps(
      persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]
  )(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    ServiceMigration.migrateAppVersions(migrationVersion, persistenceStore, appMigratingFlow)
  }

  /**
    * Loads all pod definitions from store and sets the role to Marathon's default role.
    *
    * @param persistenceStore The ZooKeeper storage.
    * @return Successful future when done.
    */
  def migratePods(
      persistenceStore: PersistenceStore[ZkId, String, ZkSerialized]
  )(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    ServiceMigration.migratePodVersions(migrationVersion, persistenceStore, podMigratingFlow)
  }
}
