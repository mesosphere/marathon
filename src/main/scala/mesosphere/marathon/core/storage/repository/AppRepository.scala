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

  def listVersions(appId: PathId): Source[OffsetDateTime, NotUsed]

  def expunge(appId: PathId): Future[Done]

  def app(appId: PathId, version: OffsetDateTime): Future[Option[AppDefinition]]

  def store(appDef: AppDefinition): Future[Done]

  def apps(): Source[AppDefinition, NotUsed]

  def currentAppVersions(): Source[AppVersion, NotUsed]
}
