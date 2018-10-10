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
  * This class implements a repository for templates. It uses the underlying [[ZooKeeperPersistenceStore]] and stores
  * [[Template]]s using its [[PathId]] to determine the storage location. The absolute Zookeeper path, built from the
  * [[base]], service's pathId and service's hashCode.
  *
  * This allows us to store multiple entries with the same [[PathId]] e.g. multiple versions of an [[mesosphere.marathon.state.AppDefinition]]
  * so that a template with an `id = /eng/foo` would be stored like:
  * {{{
  *   /base
  *     /eng
  *       /foo
  *         /834782382 <- AppDefinition.hashCode
  *         /384572239
  * }}}
  *
  * An interesting fact about Zookeeper: one can create a lot znodes in one parent znode but if you try to get all of them by calling
  * [[mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore.children()]] (on the parent znode) you are likely
  * to get an error like:
  * `
  * java.io.IOException: Packet len20800020 is out of range!
  *  at org.apache.zookeeper.ClientCnxnSocket.readLength(ClientCnxnSocket.java:112)
  *  ...
  * ```
  *
  * Turns out that ZK has a packet length limit which is 4096 * 1024 bytes by default:
  * https://github.com/apache/zookeeper/blob/0cb4011dac7ec28637426cafd98b4f8f299ef61d/src/java/main/org/apache/zookeeper/client/ZKClientConfig.java#L58
  *
  * It can be altered by setting `jute.maxbuffer` environment variable. Packet in this context is an application level
  * packet containing all the children names. Some experimentation showed that each child element in that packet has ~4bytes
  * overhead for encoding. So, let's say each child znode *name* e.g. holding Marathon app definition is 50 characters long
  * (seems like a good guess given 3-4 levels of nesting e.g. `eng_dev_databases_my-favourite-mysql-instance`), we could only
  * have: 4096 * 1024 / (50 + 4) =  ~75k children nodes until we hit the exception.
  *
  * In this class we implicitly rely on the users to *not* put too many children (apps/pods) under one parent. Since that
  * number can be quite high (~75k) I think we are fine without implementing any guards.
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