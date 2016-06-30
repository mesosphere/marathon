package mesosphere.util.state.zk

import akka.Done
import akka.util.ByteString
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.CuratorEventType._
import org.apache.curator.framework.api.{ BackgroundCallback, CuratorEvent }
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.data.{ ACL, Stat }

import scala.collection.JavaConversions._
import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration
import scala.concurrent.{ CanAwait, ExecutionContext, Future, Promise, TimeoutException }
import scala.util.{ Failure, Success, Try }

private[zk] abstract class ZkFuture[T] extends Future[T] with BackgroundCallback {
  private val promise = Promise[T]()
  override def onComplete[U](f: (Try[T]) => U)(implicit executor: ExecutionContext): Unit =
    promise.future.onComplete(f)

  override def isCompleted: Boolean = promise.isCompleted

  override def value: Option[Try[T]] = promise.future.value

  override def processResult(client: CuratorFramework, event: CuratorEvent): Unit = {
    import KeeperException.Code._
    val resultCode = KeeperException.Code.get(event.getResultCode)
    if (resultCode != OK) {
      promise.failure(KeeperException.create(resultCode))
    } else {
      promise.complete(processEvent(event))
    }
  }

  @scala.throws[Exception](classOf[Exception])
  override def result(atMost: Duration)(implicit permit: CanAwait): T = promise.future.result(atMost)

  @scala.throws[InterruptedException](classOf[InterruptedException])
  @scala.throws[TimeoutException](classOf[TimeoutException])
  override def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
    promise.future.ready(atMost)
    this
  }

  def fail(e: Throwable): Future[T] = {
    promise.tryFailure(e)
    this
  }

  protected def processEvent(event: CuratorEvent): Try[T]
}

private class CreateOrDeleteFuture extends ZkFuture[String] {
  override protected def processEvent(event: CuratorEvent): Try[String] = event.getType match {
    case CREATE | DELETE =>
      Success(event.getPath)
    case _ =>
      Failure(new IllegalArgumentException(s"${event.getType} is not a CREATE or DELETE operation"))
  }
}

case class ExistsResult(path: String, stat: Stat)
private class ExistsFuture extends ZkFuture[ExistsResult] {
  override protected def processEvent(event: CuratorEvent): Try[ExistsResult] = event.getType match {
    case EXISTS =>
      Success(ExistsResult(event.getPath, event.getStat))
    case _ =>
      Failure(new IllegalArgumentException(s"${event.getType} is not an EXISTS operation"))
  }
}

case class GetData(path: String, stat: Stat, data: ByteString)
private class GetDataFuture extends ZkFuture[GetData] {
  override protected def processEvent(event: CuratorEvent): Try[GetData] = event.getType match {
    case GET_DATA =>
      Success(GetData(event.getPath, event.getStat, ByteString(event.getData)))
    case _ =>
      Failure(new IllegalArgumentException(s"${event.getType} is not a GET_DATA operation"))
  }
}

case class SetData(path: String, stat: Stat)
private class SetDataFuture extends ZkFuture[SetData] {
  override protected def processEvent(event: CuratorEvent): Try[SetData] = event.getType match {
    case SET_DATA =>
      Success(SetData(event.getPath, event.getStat))
    case _ =>
      Failure(new IllegalArgumentException(s"${event.getType} is not a SET_DATA operation"))
  }
}

case class Children(path: String, stat: Stat, children: Seq[String])
private class ChildrenFuture extends ZkFuture[Children] {
  override protected def processEvent(event: CuratorEvent): Try[Children] = event.getType match {
    case CHILDREN =>
      Success(Children(event.getPath, event.getStat, event.getChildren.toVector))
    case _ =>
      Failure(new IllegalArgumentException(s"${event.getType} is not a CHILDREN operation"))
  }
}

private class SyncFuture extends ZkFuture[Option[Stat]] {
  override protected def processEvent(event: CuratorEvent): Try[Option[Stat]] = event.getType match {
    case SYNC =>
      Success(Option(event.getStat))
    case _ =>
      Failure(new IllegalArgumentException(s"${event.getType} is not a SYNC operation"))
  }
}

private class GetAclFuture extends ZkFuture[Seq[ACL]] {
  override protected def processEvent(event: CuratorEvent): Try[Seq[ACL]] = event.getType match {
    case GET_ACL =>
      Success(event.getACLList.toVector)
    case _ =>
      Failure(new IllegalArgumentException(s"${event.getType} is not a GET_ACL operation"))
  }
}

private class SetAclFuture extends ZkFuture[Done] {
  override protected def processEvent(event: CuratorEvent): Try[Done] = event.getType match {
    case SET_ACL =>
      Success(Done)
    case _ =>
      Failure(new IllegalArgumentException(s"${event.getType} is not a SET_ACL operation"))
  }
}

private[zk] object ZkFuture {
  def create: ZkFuture[String] = new CreateOrDeleteFuture
  def delete: ZkFuture[String] = new CreateOrDeleteFuture
  def exists: ZkFuture[ExistsResult] = new ExistsFuture
  def data: ZkFuture[GetData] = new GetDataFuture
  def setData: ZkFuture[SetData] = new SetDataFuture
  def children: ZkFuture[Children] = new ChildrenFuture
  def sync: ZkFuture[Option[Stat]] = new SyncFuture
  def acl: ZkFuture[Seq[ACL]] = new GetAclFuture
  def setAcl: ZkFuture[Done] = new SetAclFuture
}
