package mesosphere.marathon.state

import com.google.protobuf.InvalidProtocolBufferException
import org.apache.mesos.state.State
import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration.Duration

/**
 * @author Tobi Knaup
 */

class MarathonStore[S <: MarathonState[_]](state: State,
                       newState: () => S) extends PersistenceStore[S] {

  val defaultWait = Duration(3, "seconds")
  val prefix = "app:"

  import ExecutionContext.Implicits.global
  import mesosphere.util.BackToTheFuture._

  def fetch(key: String): Future[Option[S]] = {
    state.fetch(prefix + key) map {
      case Some(variable) => stateFromBytes(variable.value)
      case None => None
    }
  }

  def store(key: String, value: S): Future[Option[S]] = {
    state.fetch(prefix + key) flatMap {
      case Some(variable) =>
        state.store(variable.mutate(value.toProtoByteArray)) map {
          case Some(newVar) => stateFromBytes(newVar.value)
          case None => None
        }
      case None => None
    }
  }

  def expunge(key: String): Future[Option[Boolean]] = {
    state.fetch(prefix + key) flatMap {
      case Some(variable) =>
        state.expunge(variable) map {
          case Some(b) => Some(Boolean.unbox(b))
          case None => None
        }
      case None => None
    }
  }

  def names(): Future[Iterator[String]] = {
    // TODO use implicit conversion after it has been merged
    future {
      try {
        state.names().get().asScala
          .filter(_.startsWith(prefix))
          .map(_.replaceFirst(prefix, ""))
      } catch {
        // Thrown when node doesn't exist
        case e: ExecutionException => Seq().iterator
      }
    }
  }

  private def stateFromBytes(bytes: Array[Byte]): Option[S] = {
    try {
      val state = newState()
      state.mergeFromProto(bytes)
      Some(state)
    } catch {
      case e: InvalidProtocolBufferException => None
    }
  }
}
