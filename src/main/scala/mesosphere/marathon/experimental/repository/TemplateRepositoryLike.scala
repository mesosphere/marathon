package mesosphere.marathon
package experimental.repository

import akka.Done
import mesosphere.marathon.state.PathId

import scala.concurrent.Future
import scala.util.Try

trait TemplateRepositoryLike {

  import TemplateRepositoryLike._

  /**
    * Method stores passed template in the repository.
    *
    * @param template a template to store
    * @tparam T
    * @return
    */
  def create[T](template: Template[T]): Future[Done]

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
  def read[T](template: Template[T], version: String): Future[Try[T]]

  /**
    * Delete a given template from the repository.
    *
    * @param template template to delete
    * @tparam T
    * @return
    */
  def delete[T](template: Template[T]): Future[Done]

  /**
    * Delete a template by it's pathId and version.
    *
    * @param pathId of the
    * @param version
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
    * Method fetches children nodes of a given pathId. It can be used to fetch e.g. all versions of a give template
    * or all children of a given pathId.
    *
    * @param pathId pathId to fetch the children of
    * @return
    */
  def contents(pathId: PathId): Future[Seq[String]]

  /**
    * Method checks existence of a certain template (and it's version) in the repository.
    *
    * @param template template to check
    * @tparam T
    * @return
    */
  def exists[T](template: Template[T]): Future[Boolean]

  /**
    * Methods checks existence of a pathId in the repository.
    *
    * @param pathId pathId to check
    * @return
    */
  def exists(pathId: PathId): Future[Boolean]

  /**
    * Methods checks existence of a certain template by it's pathId and version in the repository.
    *
    * @param pathId pathId to check
    * @param version version to check
    * @return
    */
  def exists(pathId: PathId, version: String): Future[Boolean]
}

object TemplateRepositoryLike {

  /**
    * Duck typing existing [[mesosphere.marathon.state.AppDefinition]] and [[mesosphere.marathon.core.pod.PodDefinition]]
    * as templates. The common features for both are [[PathId]]s along with the abilities to encode/decode them to/from
    * a bytes array. Template is typed with the specific class type that is being decoded from the stored bytes (see
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