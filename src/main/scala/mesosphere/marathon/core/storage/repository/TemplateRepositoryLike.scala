package mesosphere.marathon
package core.storage.repository

import akka.Done
import akka.util.ByteString
import mesosphere.marathon.Protos.ServiceDefinition
import mesosphere.marathon.core.storage.repository.impl.TemplateRepository.VersionBucketPath
import mesosphere.marathon.state.{AppDefinition, PathId}

import scala.concurrent.Future

trait TemplateRepositoryLike {

  import TemplateRepositoryLike._

  def init(): Future[Done]

  def create(template: Template): Future[Versioned]

  def read(pathId: PathId, version: Int): Future[Versioned]

  def delete(pathId: PathId, version: Int): Future[Done]

  def delete(pathId: PathId): Future[Done]

  def versions(pathId: PathId): Future[Seq[Int]]

  def exists(pathId: PathId): Future[Boolean]

  def exists(pathId: PathId, version: Int): Future[Boolean]
}

object TemplateRepositoryLike {

  /**
    * A Template is simply a [[mesosphere.marathon.state.AppDefinition]] for now.
    */
  type Template = AppDefinition

  /**
    * A wrapper for the Template and it's version.
    */
  case class Versioned(template: Template, version: Int) extends BucketNode {

    def pathId = template.id

    override def bucketPath: BucketPath = VersionBucketPath(pathId, version)

    override def payload: ByteString = ByteString(template.toProtoByteArray)
  }

  object Versioned {

    def apply(data: ByteString, version: Int): Versioned = Versioned(AppDefinition.fromProto(ServiceDefinition.parseFrom(data.toArray)), version)
  }
}