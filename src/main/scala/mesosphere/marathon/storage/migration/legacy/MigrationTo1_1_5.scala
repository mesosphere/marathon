package mesosphere.marathon.storage.migration.legacy

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state._
import mesosphere.marathon.storage.LegacyStorageConfig
import mesosphere.marathon.storage.repository.{ AppRepository, GroupRepository, PodRepository }
import org.slf4j.LoggerFactory

import scala.async.Async.{ async, await }
import scala.concurrent.{ ExecutionContext, Future }

/**
  * Migrate groups:
  * Group(id = /, apps = [“/foo/bla”] )
  * —>
  * Group (id = / ,
  *   Group(id = /foo,
  *         apps = [”/foo/bla”] ))
  *
  */
class MigrationTo1_1_5(availableFeatures: Set[String], legacyConfig: Option[LegacyStorageConfig])(implicit
  ctx: ExecutionContext,
    metrics: Metrics,
    mat: Materializer) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def migrate(): Future[Done] = {
    legacyConfig.fold[Future[Done]](Future.successful(Done)) { config =>
      async {
        log.info("Start 1.1.5 migration")
        val appRepository = AppRepository.legacyRepository(config.entityStore[AppDefinition], config.maxVersions)
        val podRepository = PodRepository.legacyRepository(config.entityStore[PodDefinition], config.maxVersions)
        val groupRepository = GroupRepository.legacyRepository(config.entityStore[Group], config.maxVersions, appRepository, podRepository)

        //        We can have 3 cases here:
        //          1. App has one entry at the wrong place and must be moved:
        //
        //              Group( id = /, apps = [“/foo/bla”] )
        //              —>
        //              Group ( id = / ,
        //                Group( id = /foo, apps = [”/foo/bla”] )
        //
        //          2. App has 2 entries but both entries has the same version so the wrong one can be deleted:
        //
        //            Group( id = /, apps = [“/foo/bla, version = 1"],
        //              Group( id = /foo, apps = [“/foo/bla, version = 1"])
        //            —>
        //            Group ( id = / ,
        //              Group( id = /foo, apps = [“/foo/bla, version = 1"] )
        //
        //          3. App has 2 entries with different versions. At this point we can either fail the migration or keep one of
        //             the versions. After some discussion we decided to keep the latest version.
        //             CAUTION: this migration can potentially result in data loss:
        //
        //            Group( id = /, apps = [“/foo/bla, version = 1"],
        //              Group( id = /foo, apps = [“/foo/bla, version = 2"])
        //            —>
        //            Group ( id = / ,
        //              Group( id = /foo, apps = [“/foo/bla, version = 2"] )

        // Update root
        val root = await(groupRepository.root())
        val updatedRoot = updateGroup(root)
        validateGroup(updatedRoot)

        implicit val groupOrdering = Ordering.by[RootGroup, Timestamp](_.version)

        // Update root versions
        val rootVersions = await {
          groupRepository.rootVersions().mapAsync(Int.MaxValue) { version =>
            groupRepository.rootVersion(version)
          }.collect { case Some(r) => r }.runWith(Sink.seq).map(_.sorted)
        }

        log.info(s"Loaded root versions:  $rootVersions")
        val updatedVersions = rootVersions.map(updateGroup)
        log.info(s"Updated root versions: $updatedVersions")

        updatedVersions.foreach(validateGroup)
        await(Future.sequence(updatedVersions.map(groupRepository.storeVersion)))

        await(groupRepository.storeRoot(updatedRoot, updatedRoot.transitiveApps.toIndexedSeq, Nil, Nil, Nil))
        log.info("Finished 1.1.5 migration")
        Done
      }
    }
  }

  def updateGroup(group: RootGroup): RootGroup = {
    log.info(s"Migrating group: $group")
    // get all apps including duplicates with different versions
    val apps = allApps(group)
    // remove all apps, keeping the empty groups
    val empty = removeAllApps(group)
    // update the groups with the apps while keeping the newest app version
    val updated = apps.foldLeft(empty){ (group, app) =>
      log.debug(s"Migrating $app")
      group.updateApp(app.id, _.fold(app){ that => if (that.version.after(app.version)) that else app }, group.version)
    }
    log.info(s"Resulting group: $updated")
    updated
  }

  import mesosphere.marathon.ValidationFailedException

  implicit private val validator = RootGroup.valid(availableFeatures)

  def validateGroup(group: RootGroup): Unit = {
    // Try-catch to log the reason for failed validation
    try {
      Validation.validateOrThrow(group)
    } catch {
      case e @ ValidationFailedException(f, t) =>
        log.error(s"Validation failed for $f, because: $t")
        throw e
      case e: Exception => throw e
    }
  }

  def allApps(group: Group): Iterable[AppDefinition] = {
    group.apps.values ++ group.groupsById.values.flatMap(allApps)
  }

  def removeAllApps(rootGroup: RootGroup): RootGroup = {
    rootGroup.transitiveGroupsById.foldLeft(rootGroup) {
      case (z, (groupId, _)) => z.updateApps(groupId, _ => Group.defaultApps, rootGroup.version);
    }
  }
}
