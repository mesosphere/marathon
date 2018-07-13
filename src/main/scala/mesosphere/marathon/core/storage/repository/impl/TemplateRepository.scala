package mesosphere.marathon
package core.storage.repository.impl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.repository.TemplateRepositoryLike.{Spec, Template}
import mesosphere.marathon.core.storage.repository._
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore.Node
import mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore
import mesosphere.marathon.state._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.hashing.MurmurHash3
import scala.util.{Failure, Success}

/**
  * This class implements a repository for templates. It uses [[BucketingRepository]] (and underlying [[ZooKeeperPersistenceStore]]
  * to store templates and their versions in a tree like structure in the Zookeeper.
  *
  * Using [[BucketingRepository]] implementation, all template versions are saved like `/$base/$bucket/$safePathId/$version` where:
  * <ul>
  * <li>`$base` is simply "/templates"</li>
  * <li>`$bucket` is bucket hash of templates pathId (see [[mesosphere.marathon.core.storage.repository.impl.TemplateRepository.TemplateBucketPath]]
  *   implementation for details)</li>
  * <li> `$safePathId` is [[PathId.safePath]] (see [[mesosphere.marathon.core.storage.repository.impl.TemplateRepository.VersionBucketPath]]
  *   implementation for details)</li>
  * <li>`$version` is template version</li>
  * </ul>
  *
  * So two templates with [[PathId]]s `/eng/foo` (with versions 1 and 2) and `/sales/bar` (versions 3) will be stored like following:
  * {{{
  * /templates
  *   /01
  *     /eng_foo
  *       /1
  *       /2
  *   /05
  *     /sales_bar
  *       /3
  * }}}
  * Most methods simply use the underlying [[BucketingRepository]] methods to store templates by mapping from [[PathId]] and version
  * to [[BucketNode]] and [[BucketPath]] using [[mesosphere.marathon.core.storage.repository.impl.TemplateRepository.VersionBucketPath]]
  * and [[mesosphere.marathon.core.storage.repository.impl.TemplateRepository.TemplateBucketPath]] helper classes.
  *
  * [[create]] method takes a [[Spec]] and returns a [[Template]] which contains the spec and it's version. A version is an integer that
  * is automatically generated and assigned by the repository. Internally this repository holds a counter for every template incrementing
  * it every time a new template version is created.
  *
  * [[update()]] method is overridden and throws an [[UnsupportedOperationException]] since templates are immutable and should not
  * be updated.
  *
  * [[init()]] method initializes repository by reading all existing templates Ids and their versions (but not template content) and
  * initializing internal template version counters. This method should be called and waited for successful competition prior to any
  * other usage of the repository.
  *
  * Known pitfalls: please note that while there is one at least one version of the template, a counter is always increased monotonically.
  * However should all template versions be deleted and marathon restarted, a counter is initialized with `0`.
  * This implementation is tread-safe and provides serializability guarantees e.g. two independent [[create()]] calls are not guaranteed
  * to be executed in any particular order the same way one should not expect two independent futures to have an order.
  *
  * @param store underlying instance of [[ZooKeeperPersistenceStore]]
  * @param ec execution context
  * @param mat stream materializer
  */
class TemplateRepository(val store: ZooKeeperPersistenceStore)(implicit ec: ExecutionContext, mat: ActorMaterializer)
  extends BucketingRepository with TemplateRepositoryLike with StrictLogging {

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
      .fromFuture(store.exists(base)) // Fetch existing buckets if any exist
      .mapAsync(1)(ex => if (ex) store.children(base) else Future.successful(Seq.empty))
      .mapConcat(identity(_))
      .via(store.childrenFlow) // Fetch existing templates from all buckets
      .mapConcat(identity(_))
      .via(store.childrenFlow) // Fetch versions for all templates
      .filterNot(_.isEmpty) // Filter out templates without existing versions
      .map { versions =>
        val pathId = PathId.fromSafePath(versions.head.split("/").init.last)
        val maxVersion = versions.map(_.split("/").last.toInt).max

        // compute() method of the ConcurrentHashMap is executed atomically. It computes a mapping for the given template
        // pathId and it's maximum version number.
        counters.compute(pathId, (_, value) => if (value == null) new AtomicInteger(maxVersion) else { value.getAndSet(maxVersion); value })
      }
      .runWith(Sink.ignore)
  }

  def create(spec: Spec): Future[Template] = {
    val version = counters.compute(spec.id, (key, value) => // New template version is calculated atomically
      if (value == null) new AtomicInteger(0)
      else value
    ).incrementAndGet()

    val template = Template(spec, version)
    super.create(template).map(_ => template)
  }

  def read(pathId: PathId, version: Int): Future[Template] =
    super
      .read(VersionBucketPath(pathId, version))
      .map {
        case Success(Node(path, data)) => Template(data, version)
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