package mesosphere.util.state

import mesosphere.marathon.state.{ MarathonState, MarathonStore }
import org.apache.mesos.Protos
import org.apache.mesos.Protos.FrameworkID

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration

/**
  * Utility class for keeping track of a framework ID
  */
class FrameworkIdUtil(mStore: MarathonStore[FrameworkId], timeout: Duration, key: String = "frameworkId") {
  def fetch(): Option[FrameworkID] = {
    Await.result(mStore.fetch(key), timeout).map(_.toProto)
  }
  def store(proto: FrameworkID): FrameworkId = {
    val frameworkId = FrameworkId(proto.getValue)
    Await.result(mStore.modify(key) { _ => frameworkId }, timeout)
  }
  def expunge(): Future[Boolean] = mStore.expunge(key)
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
}

