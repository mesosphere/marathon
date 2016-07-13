package mesosphere.marathon.core.storage.repository

import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.state.{ AppDefinition, PathId }

import scala.concurrent.Future

case class AppVersion(appId: PathId, version: OffsetDateTime)

/**
  * This responsibility is in transit:
  *
  * Current state:
  * - all applications are stored as part of the root group in the group repository for every user intended change
  * - all applications are stored again in the app repository, if the deployment of that application starts
  *
  * Future plan:
  * - the applications should be always loaded via the groupManager or groupRepository.
  * - the app repository is used to store versions of the application
  *
  * Until this plan is implemented, please think carefully when to use the app repository!
  */
trait AppRepository {
  def allPathIds(): Source[PathId, NotUsed]

  def currentVersion(appId: PathId): Future[Option[AppDefinition]]

  /**
    * List all of the versions of the given app
    */
  def listVersions(appId: PathId): Source[OffsetDateTime, NotUsed]

  /**
    * Delete all versions of the given app.
    */
  def expunge(appId: PathId): Future[Done]

  /**
    * Returns the app with the supplied id and version.
    */
  def app(appId: PathId, version: OffsetDateTime): Future[Option[AppDefinition]]

  /**
    * Stores the supplied app, now the current version for that apps's id.
    */
  def store(appDef: AppDefinition): Future[Done]

  /**
    * Returns the current version for all apps.
    */
  def apps(): Source[AppDefinition, NotUsed]

  /**
    * Returns a map from PathIds to current app timestamps.
    */
  def currentAppVersions(): Source[AppVersion, NotUsed] =
    apps().map(app => AppVersion(app.id, app.version.toOffsetDateTime))
}
