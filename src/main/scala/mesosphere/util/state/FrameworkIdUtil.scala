package mesosphere.util.state

import mesosphere.marathon.state.{ Timestamp, EntityStore, MarathonState }
import org.apache.mesos.Protos
import org.apache.mesos.Protos.FrameworkID
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

/**
  * Utility class for keeping track of a framework ID
  */
class FrameworkIdUtil(mStore: EntityStore[FrameworkId], timeout: Duration, key: String = "id") {

  private[this] val log = LoggerFactory.getLogger(getClass)

  def fetch(): Option[FrameworkID] = {
    Await.result(mStore.fetch(key), timeout).map(_.toProto)
  }
  def store(proto: FrameworkID): FrameworkId = {
    log.info(s"Store framework id: $proto")
    val frameworkId = FrameworkId(proto.getValue)
    Await.result(mStore.modify(key) { _ => frameworkId }, timeout)
  }
  def expunge(): Future[Boolean] = {
    log.info(s"Expunge framework id!")
    mStore.expunge(key)
  }
}

//TODO: move logic from FrameworkID to FrameworkId (which also implies moving this class)
case class FrameworkId(id: String) extends MarathonState[Protos.FrameworkID, FrameworkId] {
  override def mergeFromProto(message: FrameworkID): FrameworkId = {
    FrameworkId(message.getValue)
  }
  override def mergeFromProto(bytes: Array[Byte]): FrameworkId = {
    mergeFromProto(Protos.FrameworkID.parseFrom(bytes))
  }
  override def toProto: FrameworkID = {
    Protos.FrameworkID.newBuilder().setValue(id).build()
  }
  override def version: Timestamp = Timestamp.zero
}

