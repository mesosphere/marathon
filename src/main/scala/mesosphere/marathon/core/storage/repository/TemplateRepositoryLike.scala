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

  def create(spec: Spec): Future[Template]

  def read(pathId: PathId, version: Int): Future[Template]

  def delete(pathId: PathId, version: Int): Future[Done]

  def delete(pathId: PathId): Future[Done]

  def versions(pathId: PathId): Future[Seq[Int]]

  def exists(pathId: PathId): Future[Boolean]

  def exists(pathId: PathId, version: Int): Future[Boolean]
}

object TemplateRepositoryLike {

  /**
    * A Spec is simply a [[mesosphere.marathon.state.AppDefinition]] as long as we don't have the new specification.
    */
  type Spec = AppDefinition

  /**
    * A template is [[Spec]] plus it's version. Since scala's case class inheritance isn't ideal and we'll have to
    * override each field, the spec itself and the version are wrapped in another case class. The important parts are the
    * [[BucketNode]] trait methods implementation which define where and what data will be saved.
    */
  case class Template(spec: Spec, version: Int) extends BucketNode {

    def pathId = spec.id

    override def bucketPath: BucketPath = VersionBucketPath(pathId, version)

    override def payload: ByteString = ByteString(spec.toProtoByteArray)
  }

  object Template {

    def apply(data: ByteString, version: Int): Template = Template(AppDefinition.fromProto(ServiceDefinition.parseFrom(data.toArray)), version)
  }
}