package mesosphere.marathon.core.storage.repository

import java.time.OffsetDateTime

import akka.stream.scaladsl.Source
import akka.{ Done, NotUsed }
import mesosphere.marathon.state.Group

import scala.concurrent.Future

trait GroupRepository {
  def root(): Future[Group]
  def rootVersions(): Source[OffsetDateTime, NotUsed]
  def rootVersion(version: OffsetDateTime): Future[Option[Group]]
  def storeRoot(group: Group): Future[Done]
  def storeRootVersion(group: Group): Future[Done]
}
