package mesosphere.marathon.state

import scala.concurrent._
import org.apache.mesos.state.State

/**
 * @author Tobi Knaup
 */

class MarathonStore[S <: MarathonState[_]](state: State,
                       newState: () => S) extends PersistenceStore[S] {

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  implicit def FutureToFutureOption[T](f: java.util.concurrent.Future[T]): Future[Option[T]] = {
    future {
      val t = f.get
      if (t == null) {
        None
      } else {
        Some(t)
      }
    }
  }

  implicit def ValueToFutureOption[T](value: T): Future[T] = {
    future {
      value
    }
  }

  def fetch(key: String): Future[Option[S]] = {
    state.fetch(key) map {
      case Some(variable) =>
        val state = newState()
        state.mergeFromProto(variable.value)
        Some(state)
      case None => None
    }
  }

  def store(key: String, value: S): Future[Option[S]] = {
    state.fetch(key) flatMap {
      case Some(variable) =>
        state.store(variable.mutate(value.toProtoByteArray)) map {
          case Some(newVar) =>
            val state = newState()
            state.mergeFromProto(newVar.value)
            Some(state)
          case None => None
        }
      case None => None
    }
  }

  def expunge(key: String): Future[Boolean] = {
    state.fetch(key) flatMap {
      case Some(variable) =>
        state.expunge(variable) map {
          case Some(b) => b
          case None => false
        }
      case None => false
    }
  }
}
