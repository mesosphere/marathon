package mesosphere.util

import java.lang.Boolean
import java.util
import java.util.UUID
import java.util.concurrent.{ Future => JFuture }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future, TimeoutException }
import scala.util.{ Failure, Success }

import com.google.protobuf.ByteString
import mesosphere.marathon.Protos
import org.apache.curator.framework._
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.utils.ZKPaths
import org.apache.mesos.state.{ State, Variable }
import org.apache.zookeeper.data.Stat

/**
  * A type of State that persists data in the form of a mesosphere.marathon.Protos.Entry
  * protocol buffer message to Zookeeper.
  *
  * Does not use JNI or the native libmesos library, unlike "ZooKeeperState"
  */
class ZookeeperDirectState(
  zNode: String,
  serverList: String,
  sessionTimeout: Duration,
  connectionTimeout: Duration)
    extends State
    with JavaScalaFutureSupport
    with Logging {

  private val eventualCuratorClient: Future[CuratorFramework] = createZookeeperClient()

  /**
    * Block until we have a connection to Zookeeper. If the max time is reached
    * an exception will be thrown.
    * *
    * @param duration the max amount of time to wait
    */
  def await(duration: Duration): Unit = {
    Await.ready(eventualCuratorClient, duration)
  }

  /**
    * Returns the client, if initialized and connected. Otherwise throws an exception
    */
  private def client: CuratorFramework = eventualCuratorClient.value match {
    case Some(Success(c))  => c
    case Some(Failure(ex)) => throw ex
    case None              => throw new TimeoutException("Haven't connected to Zookeeper yet")
  }

  /**
    * Unmarshall an Entry from Zookeeper and return it as an eventual Variable
    */
  private def handleFetch(name: String): Future[Variable] = Future {

    if (!exists(name)) {
      new LocalVariable(name)
    }
    else {
      val data: Array[Byte] = client.getData.forPath(fullPath(name))
      data.size match {
        case 0 =>
          new LocalVariable(name)
        case _ =>
          val entry = Protos.Entry.parseFrom(data)
          new LocalVariable(name, entry.getValue.toByteArray, Some(entry.getUuid.toStringUtf8))
      }
    }
  }

  /**
    * Marshall a variable to an Entry and store it in Zookeeper
    */
  private def handleStore(lv: LocalVariable): Future[Variable] = Future {

    val persistedVariable = fetch(lv.name).get.asInstanceOf[LocalVariable]
    val inputMatchesPersistedVersion = persistedVariable.storedUuid == lv.storedUuid

    if (inputMatchesPersistedVersion) {

      val entryData = mesosphere.marathon.Protos.Entry.newBuilder()
        .setName(lv.name)
        .setUuid(ByteString.copyFromUtf8(UUID.randomUUID().toString))
        .setValue(ByteString.copyFrom(lv.value()))
        .build()
        .toByteArray

      mkDir(Some(lv.name))
      client.setData().forPath(fullPath(lv.name), entryData)
      fetch(lv.name).get()
    }
    else {
      log.warn(s"Attempt was made to persist ${lv.name} from obsolete version")
      null
    }

  }

  /**
    * Remove the variable from our Zookeeper node. Returns false if deletion was not necessary.
    */
  private def handleExpunge(lv: LocalVariable): Future[Boolean] = Future {

    if (exists(lv.name)) {
      client.delete().forPath(fullPath(lv.name))
      true
    }
    else false
  }

  /**
    * Return the children of a Zookeeper node
    */
  private def handleNames: Future[util.Iterator[String]] = {
    Future { client.getChildren.forPath(zNode).iterator() }
  }

  private def fullPath(name: String) = s"$zNode/$name"

  private def mkDir(name: Option[String] = None, zkClient: CuratorFramework = client): Unit = {
    val path = name map fullPath getOrElse zNode
    ZKPaths.mkdirs(zkClient.getZookeeperClient.getZooKeeper, path)
  }

  private def exists(name: String): Boolean = {
    client.checkExists().forPath(fullPath(name)) != null
  }

  /**
    * Creates our Zookeeper client
    */
  private def createZookeeperClient(): Future[CuratorFramework] = {

    val RETRY_POLICY = new ExponentialBackoffRetry(1000, 4)

    val client = CuratorFrameworkFactory.newClient(
      serverList,
      sessionTimeout.toMillis.toInt,
      connectionTimeout.toMillis.toInt,
      RETRY_POLICY
    )

    client.start()

    // Make sure we have a successful connection before returning the client
    Future {
      val connected = client.getZookeeperClient.blockUntilConnectedOrTimedOut()
      if (!connected) throw new TimeoutException("Could not connect to Zookeeper")

      // Create our znode, if necessary
      mkDir(zkClient = client)

      client
    }
  }

  // Wrap our local Scala-Future-returning methods with Java-Future-returning methods
  // to satisfy the base State interface.
  override def names(): JFuture[util.Iterator[String]] = handleNames.asJava(sessionTimeout)
  override def fetch(name: String): JFuture[Variable] = handleFetch(name).asJava(sessionTimeout)
  override def store(v: Variable): JFuture[Variable] =
    handleStore(v.asInstanceOf[LocalVariable]).asJava(sessionTimeout)
  override def expunge(v: Variable): JFuture[Boolean] =
    handleExpunge(v.asInstanceOf[LocalVariable]).asJava(sessionTimeout)

}

/**
  * An implementation of the JNI Variable (used by State, among others). Not actually using JNI.
  */
class LocalVariable(
    val name: String,
    val data: Array[Byte] = Array[Byte](),
    val storedUuid: Option[String] = None) extends Variable {

  override def value(): Array[Byte] = data
  override def mutate(value: Array[Byte]): Variable = new LocalVariable(name, value, storedUuid)
  def isPersisted: Boolean = data.nonEmpty
}

