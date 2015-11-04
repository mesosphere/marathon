package mesosphere.marathon.state

import mesosphere.marathon.LeadershipCallback
import mesosphere.util.ThreadPoolContext
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/**
  * The entity store cache caches all current versions from the underlying store.
  * During election we prefetch al unversioned entries.
  *
  * Idea:
  * - old versions (versioned entries) are never cached
  * - we always cache the current version
  * - on elected:
  *    - clear everything
  *    - load all current versions
  *  - on defeated:
  *    - clear everything
  */
class EntityStoreCache[T <: MarathonState[_, T]](store: EntityStore[T])
    extends EntityStore[T] with LeadershipCallback with VersionedEntry {

  private[state] val cache = new TrieMap[String, Option[T]]()
  private[this] implicit val ec = ThreadPoolContext.context
  private[this] val log = LoggerFactory.getLogger(getClass)

  override def fetch(key: String): Future[Option[T]] = {
    if (noVersionKey(key)) {
      Future.successful{
        cache.get(key) match {
          case Some(t) => t
          case _       => None
        }
      }
    }
    else {
      //if we need to fetch a versioned entry, try if this is the latest version we have in the cache
      //otherwise let the underlying store fetch that entry.
      val id = idFromVersionKey(key)
      cache.get(id) match {
        case Some(Some(t)) if key == versionKey(id, t.version) => Future.successful(Some(t))
        case _ => store.fetch(key)
      }
    }
  }

  override def modify(key: String, onSuccess: (T) => Unit = _ => ())(update: Update): Future[T] = {
    def onModified(t: T): Unit = {
      cache.update(key, if (noVersionKey(key)) Some(t) else None)
      onSuccess(t)
    }
    store.modify(key, onModified)(update)
  }

  override def names(): Future[Seq[String]] = Future.successful(cache.keySet.toSeq)

  override def expunge(key: String, onSuccess: () => Unit = () => ()): Future[Boolean] = {
    def onExpunged(): Unit = {
      cache.remove(key)
      onSuccess()
    }
    store.expunge(key, onExpunged)
  }

  override def onElected: Future[Unit] = {
    cache.clear()

    def preloadEntry(nextName: String): Future[Unit] = {
      store.fetch(nextName).map {
        case Some(t) => cache.update(nextName, Some(t))
        case None    => log.warn(s"Expected to find entry $nextName in store $store")
      }
    }

    def preloadEntries(unversionedNames: Seq[String]): Future[Unit] = {
      unversionedNames.foldLeft[Future[Unit]](Future.successful(())) { (completed, nextName) =>
        completed.flatMap { _ => preloadEntry(nextName) }
      }
    }

    def handleEntries(names: Seq[String]): Future[Unit] = {
      val (unversionedNames, versionedNames) = names.partition(noVersionKey)
      if (log.isDebugEnabled) {
        log.debug(s"$store Preload and cache entries: $unversionedNames and versioned entries $versionedNames")
      }
      //add keys with None for version entries
      versionedNames.foreach(cache.put(_, None))
      //add key with loaded values
      preloadEntries(unversionedNames)
    }

    store.names().flatMap(handleEntries)
  }

  override def onDefeated: Future[Unit] = {
    log.debug(s"$store Clear all cached entries")
    cache.clear()
    Future.successful(())
  }

  override def toString: String = s"EntityStoreCache($store)"
}
