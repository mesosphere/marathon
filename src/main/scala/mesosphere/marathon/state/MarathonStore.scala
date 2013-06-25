package mesosphere.marathon.state

import scala.concurrent._
import org.apache.mesos.state.ZooKeeperState
import java.util.concurrent.TimeUnit
import mesosphere.marathon.api.v1.ServiceDefinition

/**
 * @author Tobi Knaup
 */

class MarathonStore(zkHost: String, timeoutSeconds: Long, path: String) extends PersistenceStore[ServiceDefinition] {

  val state = new ZooKeeperState(zkHost, timeoutSeconds, TimeUnit.SECONDS, path)

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  def fetch(key: String): Future[Option[ServiceDefinition]] = {
    future {
      val bytes = state.fetch(key).get.value
      if (bytes.isEmpty) {
        None
      } else {
        val sd = ServiceDefinition.parseFromProto(bytes)
        Some(sd)
      }
    }
  }

  def store(key: String, value: ServiceDefinition): Future[Option[ServiceDefinition]] = {
    future {
      val oldVar = state.fetch(key).get
      val newVar = oldVar.mutate(value.toProtoByteArray)
      val bytes = state.store(newVar).get.value
      if (bytes.isEmpty) {
        None
      } else {
        Some(ServiceDefinition.parseFromProto(bytes))
      }
    }
  }

  def expunge(key: String): Future[Boolean] = {
    future {
      val variable = state.fetch(key).get
      state.expunge(variable).get
    }
  }
}

object MarathonStore {

  val defaultHost = "localhost:2181"
  val defaultTimeout = 10
  val defaultPath = "/marathon-state"

  def apply() = new MarathonStore(defaultHost, defaultTimeout, defaultPath)

  def apply(zkHost: String) = new MarathonStore(zkHost, defaultTimeout, defaultPath)

  def apply(zkHost: String, path: String) = new MarathonStore(zkHost, defaultTimeout, path)

  def apply(zkHost: String, timeoutSeconds: Long, path: String) = new MarathonStore(zkHost, timeoutSeconds, path)

}
