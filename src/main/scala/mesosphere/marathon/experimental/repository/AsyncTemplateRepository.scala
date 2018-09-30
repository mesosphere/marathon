package mesosphere.marathon
package experimental.repository

import java.nio.file.Paths

import akka.Done
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.Node
import mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore
import mesosphere.marathon.experimental.repository.TemplateRepositoryLike.Template
import mesosphere.marathon.state.PathId

import scala.concurrent.{ExecutionContext, Future}
import scala.language.reflectiveCalls
import scala.util.{Failure, Success, Try}

/**
  * This class implements a repository for templates.
  *
  * @param underlying underlying instance of [[ZooKeeperPersistenceStore]]
  * @param ec execution context
  */
class AsyncTemplateRepository(val underlying: ZooKeeperPersistenceStore, val base: String)(implicit ec: ExecutionContext)
  extends StrictLogging with TemplateRepositoryLike {

  /**
    * Return a version of the template. A stable hash should be used for the entries (e.g. [[scala.util.hashing.MurmurHash3.productHash]])
    * which is already the case for [[mesosphere.marathon.state.AppDefinition.hashCode]] and [[mesosphere.marathon.core.pod.PodDefinition.hashCode]]
    *
    * @param template
    * @tparam T
    * @return
    */
  def version[T](template: Template[T]): String = Math.abs(template.hashCode).toString

  /**
    * Return an absolute Zookeeper path, built from the [[base]], service's pathId and service's hashCode.
    * This allows us to store multiple entries with the same [[PathId]] e.g. multiple versions of an [[mesosphere.marathon.state.AppDefinition]]
    * with `id = /eng/foo` would be stored like:
    * {{{
    *   /base
    *     /eng
    *       /foo
    *         /834782382 <- AppDefinition.hashCode
    *         /384572239
    * }}}
    *
    * @param entry
    * @return
    */
  def toPath[T](template: Template[T]): String = toPath(template.id, version(template))
  def toPath(pathId: PathId, version: String = "") = Paths.get("/", base, pathId.toString, version).toString

  def toNode[T](template: Template[T]) = Node(toPath(template), ByteString(template.toProtoByteArray))

  def toTemplate[T](maybeNode: Try[Node], template: Template[T]): Try[T] = maybeNode match {
    case Success(node) => Success(template.mergeFromProto(node.data.toArray))
    case Failure(ex) => Failure(ex)
  }

  override def create[T](template: Template[T]): Future[Done] = {
    underlying
      .create(toNode(template))
      .map(_ => Done)
  }

  override def read[T](template: Template[T], version: String): Future[Try[T]] = {
    underlying
      .read(toPath(template.id, version))
      .map(maybeNode => toTemplate(maybeNode, template))
  }

  override def delete(pathId: PathId, version: String): Future[Done] = {
    underlying
      .delete(toPath(pathId, version))
      .map(_ => Done)
  }

  override def delete(pathId: PathId): Future[Done] = delete(pathId, version = "")
  override def delete[T](template: Template[T]): Future[Done] = delete(template.id, version(template))

  override def contents(pathId: PathId): Future[Seq[String]] = {
    underlying
      .children(toPath(pathId), absolute = false)
      .map(children =>
        children.map(child => Paths.get(pathId.toString, child).toString)
      )
  }

  override def exists(pathId: PathId, version: String): Future[Boolean] = underlying.exists(toPath(pathId, version))
  override def exists(pathId: PathId): Future[Boolean] = exists(pathId, version = "")
  override def exists[T](template: Template[T]): Future[Boolean] = exists(template.id, version(template))
}