package mesosphere.marathon
package experimental.repository

import java.nio.file.Paths

import akka.Done
import mesosphere.marathon.state.PathId

import scala.concurrent.Future

trait TemplateRepositoryLike {

  import TemplateRepositoryLike._

  /**
    * Holds the root path underneath which, all templates are saved in the storage e.g. a template with a pathId `/eng/foo`
    * is stored underneath `/${base}/eng/foo`
    *
    * @return
    */
  def base: String

  /**
    * Return a version of the template. A stable hash should be used for the entries (e.g. [[scala.util.hashing.MurmurHash3.productHash]])
    * which is already the case for [[mesosphere.marathon.state.AppDefinition.hashCode]] and [[mesosphere.marathon.core.pod.PodDefinition.hashCode]]
    *
    * @param template
    * @tparam T
    * @return
    */
  def version(template: Template[_]): String = Math.abs(template.hashCode).toString

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
  def storePath(template: Template[_]): String = storePath(template.id, version(template))
  def storePath(pathId: PathId, version: String = "") = Paths.get("/", base, pathId.toString, version).toString

  /**
    * Method stores passed template in the repository.
    *
    * @param template a template to store
    * @tparam T
    * @return a version of the created template
    */
  def create(template: Template[_]): Future[String]

  /**
    * Method reads a template from the repository. One needs to pass a dummy instance with default values and proper pathId
    * to read a stored one. This is because decoding bytes is different for app and pod definitions (see
    * [[mesosphere.marathon.state.AppDefinition.mergeFromProto()]] and [[mesosphere.marathon.core.pod.PodDefinition.mergeFromProto()]]).
    * Since the repository can store/fetch any objects that has a [[PathId]], this is how one passes the way to decode
    * fetched bytes.
    *
    * @param template a default template instance with the correct pathId set
    * @param version a version of the template to read
    * @tparam T
    * @return
    */
  def read[T](template: Template[T], version: String): Future[T]

  /**
    * Delete a given template from the repository.
    *
    * @param template template to delete
    * @tparam T
    * @return
    */
  def delete(pathId: PathId, version: String): Future[Done]

  /**
    * Delete any pathId from the repository. This can be used to e.g. delete all versions of the given template
    * or delete a node containing multiple entries.
    *
    * @param pathId pathId to delete
    * @return
    */
  def delete(pathId: PathId): Future[Done]

  /**
    * Method checks existence of a certain template (and it's version) in the repository.
    *
    * @param template template to check
    * @tparam T
    * @return
    */
  def exists(pathId: PathId, version: String): Future[Boolean]

  /**
    * Methods checks existence of a pathId in the repository.
    *
    * @param pathId pathId to check
    * @return
    */
  def exists(pathId: PathId): Future[Boolean]

  /**
    * Method fetches children nodes of a given pathId. It can be used to fetch e.g. all versions of a give template
    * or all children of a given pathId.
    *
    * @param pathId pathId to fetch the children of
    * @return
    */
  def contents(pathId: PathId): Future[Seq[String]]
}

object TemplateRepositoryLike {

  /**
    * Duck typing existing [[mesosphere.marathon.state.AppDefinition]] and [[mesosphere.marathon.core.pod.PodDefinition]]
    * as templates. The common features for both are [[PathId]]s along with the abilities to encode/decode them to/from
    * a bytes array. Template is typed with the concrete class type that is being decoded from the stored bytes (see
    * `mergeFromProto` method.
    *
    */
  type Template[T] = {
    def id: PathId
    def toProtoByteArray: Array[Byte]
    def mergeFromProto(bytes: Array[Byte]): T
    def hashCode: Int
  }
}