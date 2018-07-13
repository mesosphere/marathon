package mesosphere.marathon
package core.storage.repository.impl

import akka.Done
import akka.stream.ActorMaterializer
import com.github.blemale.scaffeine.{AsyncLoadingCache, Scaffeine}
import mesosphere.marathon.core.storage.repository.TemplateRepositoryLike.{Spec, Template}
import mesosphere.marathon.core.storage.zookeeper.ZooKeeperPersistenceStore
import mesosphere.marathon.state.PathId

import scala.async.Async._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * This is a cached version of the [[TemplateRepository]]. It uses [[https://github.com/blemale/scaffeine Scaffeine library]]
  * which itself is a Scala wrapper over the [[https://github.com/ben-manes/caffeine Caffeine cache]]. It supports asynchronous
  * loading of cache elements, size-based eviction, time-based expiration of elements etc.
  *
  * All template versions are saved in the cache using [[CachedTemplateRepository#toCacheKey()]]
  * method e.g. a template with pathId=/foo/bar and version=1 will be saved using the key `foo_bar:1`.
  *
  * Basically this class wraps [[TemplateRepository]] methods with the calls to cache. [[read()]] method goes directly to cache
  * and the element will be loaded if not already exists.
  *
  * Note that this implementation is tread-safe and provides serializability guarantees e.g. two independent [[create()]]
  * calls are not guaranteed to be executed in any particular order the same way one should not expect two independent futures
  * to have an order.
  *
  * Known pitfalls: an operation e.g. [[delete()]] may fail leaving the cache and the underlying store in an inconsistent
  * state: e.g. the template was removed from the store but not from the cache. This is very unlikely but still possible.
  * In this case the operation's future will fail and it is the responsibility of this repository user to react accordingly.
  * [[delete()]] operation are idempotent and can be safely repeated while [[create()]] operation will increment the template
  * version and create a new one. In this case the "failed" template version will have to be removed.
  *
  * @param store underlying instance of [[ZooKeeperPersistenceStore]]
  * @param cacheMaximumSize maximum cache size
  * @param cacheExpireAfter cached element expiration duration
  */

@SuppressWarnings(Array("all")) // async/await
//format:off
class CachedTemplateRepository(
    override val store: ZooKeeperPersistenceStore,
    val cacheMaximumSize: Int = 10000,
    val cacheExpireAfter: Duration = 30.minutes)(implicit ec: ExecutionContext, mat: ActorMaterializer)
  extends TemplateRepository(store) {

  import CachedTemplateRepository._

  // We can cache aggressively since template versions are immutable and once created/fetched can
  // stay in cache forever. The key is template's relative storage path: {PathId.safePath}:{Version} e.g. `eng_sleep:1`
  val cache: AsyncLoadingCache[String, Template] =
    Scaffeine()
      .recordStats()
      .expireAfterWrite(cacheExpireAfter)
      .maximumSize(cacheMaximumSize.toLong)
      .buildAsyncFuture { (key: String) =>
        val (pathId, version) = fromCacheKey(key)
        super.read(pathId, version)
      }

  override def create(spec: Spec): Future[Template] = async {
    val versioned = await(super.create(spec))
    cache.put(toCacheKey(versioned), Future.successful(versioned))
    versioned
  }

  override def read(pathId: PathId, version: Int): Future[Template] =
    cache.get(toCacheKey(pathId, version))

  override def delete(pathId: PathId): Future[Done] = async {
    val versions = await(super.versions(pathId)) // Get all existing template versions
    val deleted = await(super.delete(pathId)) // Delete template from the store
    cache.synchronous().invalidateAll(versions.map(toCacheKey(pathId, _))) // Invalidate all cached versions
    Done
  }

  override def delete(pathId: PathId, version: Int): Future[Done] = async {
    val deleted = super.delete(pathId, version) // Delete template node from the store
    cache.synchronous().invalidate(toCacheKey(pathId, version)) // And invalidate cached version
    Done
  }
}
//format:on

object CachedTemplateRepository {
  def toCacheKey(template: Template): String = toCacheKey(template.pathId, template.version)
  def toCacheKey(pathId: PathId, version: Int): String = s"${pathId.safePath}:$version"
  def fromCacheKey(key: String): (PathId, Int) = (PathId.fromSafePath(key.split(":")(0)), key.split(":")(1).toInt)
}
