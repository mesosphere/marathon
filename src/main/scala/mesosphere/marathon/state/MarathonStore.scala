package mesosphere.marathon.state

import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.util.{ LockManager, ThreadPoolContext }
import mesosphere.util.state.PersistentStore
import mesosphere.marathon.metrics.Metrics.Histogram
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.control.NonFatal

class MarathonStore[S <: MarathonState[_, S]](
    store: PersistentStore,
    metrics: Metrics,
    newState: () => S,
    prefix: String)(implicit ct: ClassTag[S]) extends EntityStore[S] {

  import scala.concurrent.ExecutionContext.Implicits.global
  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] lazy val lockManager = LockManager.create()
  protected[this] def metricsPrefix = MetricPrefixes.SERVICE
  protected[this] val bytesRead: Histogram =
    metrics.histogram(metrics.name(metricsPrefix, getClass, s"${ct.runtimeClass.getSimpleName}.read-data-size"))
  protected[this] val bytesWritten: Histogram =
    metrics.histogram(metrics.name(metricsPrefix, getClass, s"${ct.runtimeClass.getSimpleName}.write-data-size"))

  def fetch(key: String): Future[Option[S]] = {
    log.debug(s"Fetch $prefix$key")
    store.load(prefix + key)
      .map {
        _.map { entity =>
          bytesRead.update(entity.bytes.length)
          stateFromBytes(entity.bytes.toArray)
        }
      }
      .recover(exceptionTransform(s"Could not fetch ${ct.runtimeClass.getSimpleName} with key: $key"))
  }

  def modify(key: String, onSuccess: (S) => Unit = _ => ())(f: Update): Future[S] = {
    lockManager.executeSequentially(key) {
      log.debug(s"Modify $prefix$key")
      val res = store.load(prefix + key).flatMap {
        case Some(entity) =>
          bytesRead.update(entity.bytes.length)
          val updated = f(() => stateFromBytes(entity.bytes.toArray))
          val updatedEntity = entity.withNewContent(updated.toProtoByteArray)
          bytesWritten.update(updatedEntity.bytes.length)
          store.update(updatedEntity)
        case None =>
          val created = f(() => newState()).toProtoByteArray
          bytesWritten.update(created.length)
          store.create(prefix + key, created)
      }
      res.map { entity =>
        val result = stateFromBytes(entity.bytes.toArray)
        onSuccess(result)
        result
      }.recover(exceptionTransform(s"Could not modify ${ct.runtimeClass.getSimpleName} with key: $key"))
    }
  }

  def expunge(key: String, onSuccess: () => Unit = () => ()): Future[Boolean] = lockManager.executeSequentially(key) {
    log.debug(s"Expunge $prefix$key")
    store.delete(prefix + key).map { result =>
      onSuccess()
      result
    }.recover(exceptionTransform(s"Could not expunge ${ct.runtimeClass.getSimpleName} with key: $key"))
  }

  def names(): Future[Seq[String]] = {
    store.allIds()
      .map {
        _.collect {
          case name: String if name startsWith prefix => name.replaceFirst(prefix, "")
        }
      }
      .recover(exceptionTransform(s"Could not list names for ${ct.runtimeClass.getSimpleName}"))
  }

  private[this] def exceptionTransform[T](errorMessage: String): PartialFunction[Throwable, T] = {
    case NonFatal(ex) => throw new StoreCommandFailedException(errorMessage, ex)
  }

  private def stateFromBytes(bytes: Array[Byte]): S = {
    newState().mergeFromProto(bytes)
  }

  override def toString: String = s"MarathonStore($prefix)"
}
