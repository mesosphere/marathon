package mesosphere.marathon
package core.storage.repository.impl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.repository.TemplateRepositoryLike.{Template, Versioned}
import mesosphere.marathon.core.storage.repository._
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.Node
import mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore
import mesosphere.marathon.state._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.hashing.MurmurHash3
import scala.util.{Failure, Success}

class TemplateRepository(val store: ZooKeeperPersistenceStore)(implicit ec: ExecutionContext, mat: ActorMaterializer)
  extends BucketingRepository with StrictLogging with TemplateRepositoryLike {

  import TemplateRepository._

  override val base: String = "/templates"

  /**
    * We store a counter for the template versions separately. It's helpful when calculating next template
    * version. A counter is initialized by the current template max version number in the [[init()]] method.
    */
  val counters = new ConcurrentHashMap[PathId, AtomicInteger]()

  /**
    * Upon repository creation, we initialize [[counters]] map with max version number for all existing templates.
    * This methods should be called (and waited for successful execution) prior to any other repository method.
    */
  def init(): Future[Done] = {
    Source
      .fromFuture(store.exists(base))         // Fetch existing buckets if any exist
      .mapAsync(1)(ex => if (ex) store.children(base) else Future.successful(Seq.empty))
      .mapConcat(identity(_))
      .via(store.childrenFlow)                // Fetch existing templates from all buckets
      .mapConcat(identity(_))
      .via(store.childrenFlow)                // Fetch versions for all templates
      .filterNot(_.isEmpty)                   // Filter out templates without existing versions
      .map { versions =>
        val pathId = PathId.fromSafePath(versions.head.split("/").init.last)
        val maxVersion = versions.map(_.split("/").last.toInt).max

        // compute() method of the ConcurrentHashMap is executed atomically. It computes a mapping for the give template
        // pathId and it's maximum version number.
        counters.compute(pathId, (_, value) => if (value == null) new AtomicInteger(maxVersion) else { value.getAndSet(maxVersion); value })
      }
      .runWith(Sink.ignore)
  }

  def create(template: Template): Future[Versioned] = {
    val version = counters.compute(template.id, (key, value) => // New template version is calculated atomically
      if (value == null) new AtomicInteger(0)
      else value
    ).incrementAndGet()

    val versioned = Versioned(template, version)
    super.create(versioned).map(_ => versioned)
  }

  def read(pathId: PathId, version: Int): Future[Versioned] =
    super
      .read(VersionBucketPath(pathId, version))
      .map {
        case Success(Node(path, data)) => Versioned(data, version)
        case Failure(e) => throw e
      }

  def delete(pathId: PathId, version: Int): Future[Done] =
    super
      .delete(VersionBucketPath(pathId, version))
      .map(_ => Done)

  def delete(pathId: PathId): Future[Done] =
    super
      .delete(TemplateBucketPath(pathId))
      .map(_ => counters.remove(pathId))
      .map(_ => Done)

  override def update(bucketNode: BucketNode): Future[String] = throw new UnsupportedOperationException("Updating templates is not permitted")
  override def updateFlow: Flow[BucketNode, String, NotUsed] = throw new UnsupportedOperationException("Updating templates is not permitted")

  def versions(pathId: PathId): Future[Seq[Int]] =
    super
      .children(TemplateBucketPath(pathId))
      .map(children => children.map(child => child.split("/").last.toInt))

  def exists(pathId: PathId): Future[Boolean] = super.exists(TemplateBucketPath(pathId))
  def exists(pathId: PathId, version: Int): Future[Boolean] = super.exists(VersionBucketPath(pathId, version))
}

object TemplateRepository {

  /**
    * We use [[PathId]] of the template to determine the bucket hash. To keep bucket hash stable we use
    * [[MurmurHash3.productHash()]] hash function.
    */
  def pathIdHash(pathId: PathId): Int = Math.abs(MurmurHash3.productHash(pathId))

  case class TemplateBucketPath(pathId: PathId) extends BucketPath {

    override def bucketHash: Int = pathIdHash(pathId)

    /**
      * Relative path for a template e.g. for a template with a `PathId=/eng/foo` a relative store path is returned: `/eng_foo`
      */
    override def relativePath: String = s"${pathId.safePath}"
  }

  case class VersionBucketPath(pathId: PathId, version: Int) extends BucketPath {

    override def bucketHash: Int = pathIdHash(pathId)
    /**
      * Relative path for a versions of the template e.g. with a `PathId=/eng/foo` and a `version=1`, a relative store path is returned: `/eng_foo/1`
      */
    override def relativePath: String = s"${pathId.safePath}/$version"
  }
}