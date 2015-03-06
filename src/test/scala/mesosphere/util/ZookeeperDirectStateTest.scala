package mesosphere.util

import java.util.concurrent.TimeUnit._

import scala.concurrent.duration.Duration
import scala.util.Random

import mesosphere.marathon.MarathonSpec
import org.apache.curator.test.TestingServer
import org.apache.mesos.state.{ Variable, State, ZooKeeperState }
import scala.collection.JavaConverters._

import org.scalatest.Matchers

class ZookeeperDirectStateTest
    extends MarathonSpec
    with StateTesting
    with ZookeeperTestServerSupport
    with RandomTestUtils {

  val znode = "/" + randomString(10)
  val timeout = Duration(5, SECONDS)

  lazy val zkJNIState = new ZooKeeperState(
    zkServerList,
    timeout.toSeconds,
    SECONDS,
    znode
  )

  lazy val zkDirectState = new ZookeeperDirectState(
    znode,
    zkServerList,
    timeout,
    timeout
  )
  zkDirectState.await(timeout)

  // Run the standard suite of tests on both State implementations
  testsFor(testState(zkDirectState, "ZookeeperDirectState - "))
  testsFor(testState(zkJNIState, "ZooKeeperState - "))

  // Run tests to ensure the State implementations are fully compatible
  testsFor(interstateTesting(zkDirectState, zkJNIState))
  testsFor(interstateTesting(zkJNIState, zkDirectState))
  testsFor(interstateTesting(zkJNIState, zkJNIState))
  testsFor(interstateTesting(zkDirectState, zkDirectState))

}

trait StateTesting extends Matchers with RandomTestUtils { self: MarathonSpec =>

  def testState(state: State, prefix: String): Unit = {

    def fetch(n: String): Variable = state.fetch(n).get()
    def store(v: Variable): Variable = state.store(v).get()
    def storeData(n: String, data: Array[Byte]): Variable = store(fetch(n).mutate(data))
    def expunge(v: Variable): Boolean = state.expunge(v).get()
    def names: List[String] = state.names().get().asScala.toList

    test(prefix + "Fetching a non-existent value should return a variable with empty data") {
      fetch(randomString(20)).value should be (Nil)
    }

    test(prefix + "Fetching an existent value should return a variable with valid data") {
      val name = randomString(20)
      val data = randomString(20)
      storeData(name, data.getBytes)
      val returnedData = new String(fetch(name).value())
      data should equal(returnedData)
    }

    test(prefix + "Storing an out-of-date variable should fail with a null result") {
      val name = randomString(20)
      val data = randomString(20)
      val version1 = storeData(name, data.getBytes)

      val version2 = store(fetch(name).mutate("Hi!".getBytes))

      store(version1.mutate("Hello!".getBytes)) should be(null)
    }

    test(prefix + "Storing a current variable should return the new variable") {
      val name = randomString(20)
      val data = randomString(20)
      val version1 = storeData(name, data.getBytes)
      val version2 = store(version1.mutate("Hi!".getBytes))
      new String(version2.value()) should equal("Hi!")
    }

    test(prefix + "Deleting a non-existent variable should return false") {
      expunge(fetch(randomString(20))) should equal(false)
    }

    test(prefix + "Deleting an existing variable should return true") {
      val name = randomString(20)
      val variable = storeData(name, "Yo".getBytes)
      expunge(variable) should equal(true)

      // Make sure the variable is really gone
      expunge(variable) should equal(false)
      fetch(name).value().size should equal(0)
      fetch(name).value().size should equal(0)
    }

    test(prefix + "Names should include stored entries") {
      val name = randomString(20)
      val variable = storeData(name, "Hi There".getBytes)
      names should contain(name)
    }

    test(prefix + "Names should not include non-existent entries") {
      val name = randomString(20)
      names should not contain name
    }

    test(prefix + "Names should not include fetched-but-not-stored entries") {
      val name = randomString(20)
      fetch(name)
      names should not contain name
    }

  }

  def interstateTesting(src: State, dest: State): Unit = {

    test(s"${src.getClass.getName} to ${dest.getClass.getName} persistence should work") {
      val name = randomString(20)
      val data = randomString(20).getBytes
      src.store(src.fetch(name).get.mutate(data)).get

      dest.fetch(name).get.value() should equal(data)
    }
  }

}

object ZookeeperTestServer {
  val server = new TestingServer()
}

trait ZookeeperTestServerSupport {
  val zkServerList: String = ZookeeperTestServer.server.getConnectString
}

trait RandomTestUtils {
  def randomString(size: Int) = new String(Random.alphanumeric.take(size).toArray)
}