package mesosphere.util.state.mesos

import mesosphere.marathon.StoreCommandFailedException
import mesosphere.util.BackToTheFuture.Timeout
import mesosphere.util.ThreadPoolContext
import mesosphere.util.state.{ PersistentEntity, PersistentStore }
import org.apache.mesos.state.{ State, Variable }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

class MesosStateStore(state: State, timeout: Duration) extends PersistentStore {

  private[this] val log = LoggerFactory.getLogger(getClass)
  implicit val timeoutDuration = Timeout(timeout)
  implicit val ec = ExecutionContext.Implicits.global
  import mesosphere.util.BackToTheFuture.futureToFuture

  override def load(key: ID): Future[Option[PersistentEntity]] = {
    futureToFuture(state.fetch(key))
      .map(throwOnNull)
      .map { variable => if (entityExists(variable)) Some(MesosStateEntity(key, variable)) else None }
      .recover(mapException(s"Can not load entity with key $key"))
  }

  override def create(key: ID, content: IndexedSeq[Byte]): Future[PersistentEntity] = {
    futureToFuture(state.fetch(key))
      .map(throwOnNull)
      .flatMap { variable =>
        if (entityExists(variable)) throw new StoreCommandFailedException(s"Entity with id $key already exists!")
        else futureToFuture(state.store(variable.mutate(content.toArray))).map(MesosStateEntity(key, _))
      }
      .recover(mapException(s"Can not create entity with key $key"))
  }

  override def update(entity: PersistentEntity): Future[PersistentEntity] = entity match {
    case MesosStateEntity(id, v) =>
      futureToFuture(state.store(v))
        .recover(mapException(s"Can not update entity with key ${entity.id}"))
        .map(throwOnNull)
        .map(MesosStateEntity(id, _))

    case _ => throw new IllegalArgumentException("Can not handle this kind of entity")
  }

  override def delete(key: ID): Future[Boolean] = {
    futureToFuture(state.fetch(key))
      .map(throwOnNull)
      .flatMap { variable =>
        futureToFuture(state.expunge(variable))
          .map{
            case java.lang.Boolean.TRUE  => true
            case java.lang.Boolean.FALSE => false
          }
      }
      .recover(mapException(s"Can not delete entity with key $key"))
  }

  override def allIds(): Future[Seq[ID]] = {
    futureToFuture(state.names())
      .map(_.asScala.toSeq)
      .recover {
        case NonFatal(ex) =>
          // TODO: Currently this code path is taken when the zookeeper path does not exist yet. It would be nice
          // to not log this as a warning.
          //
          // Unfortunately, this results in a NullPointerException in `throw e.getCause()` in BackToTheFuture because
          // the native mesos code returns an ExecutionException without cause. Therefore, we cannot robustly
          // differentiate between exceptions which are "normal" and exceptions which indicate real errors
          // and we have to log them all.
          log.warn(s"Exception while calling $getClass.allIds(). " +
            s"This problem should occur only with an empty zookeeper state. " +
            s"In that case, you can ignore this message", ex)
          Seq.empty[ID]
      }
  }

  private[this] def entityExists(variable: Variable): Boolean = variable.value().nonEmpty

  private[this] def throwOnNull[T](t: T): T = {
    Option(t) match {
      case Some(value) => value
      case None        => throw new StoreCommandFailedException("Null returned from state store!")
    }
  }

  private[this] def mapException[T](message: String): PartialFunction[Throwable, T] = {
    case NonFatal(ex) => throw new StoreCommandFailedException(message, ex)
  }
}

case class MesosStateEntity(id: String, variable: Variable) extends PersistentEntity {
  override def bytes: IndexedSeq[Byte] = variable.value()
  override def withNewContent(bytes: IndexedSeq[Byte]): PersistentEntity = {
    copy(variable = variable.mutate(bytes.toArray))
  }
}
