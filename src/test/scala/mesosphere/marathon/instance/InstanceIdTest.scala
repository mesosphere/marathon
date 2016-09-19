package mesosphere.marathon.instance

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId._
import org.scalatest.{ FunSuite, Matchers }
import org.apache.mesos

class InstanceIdTest extends FunSuite with Matchers {

  test("AppIds can be converted to InstanceIds and back to AppIds") {
    val appId = "/test/foo/bla/rest".toPath
    val instanceId = Instance.Id.forRunSpec(appId)
    instanceId.runSpecId should equal(appId)
  }

  test("InstanceIds can be converted to TaskIds without container name") {
    val appId = "/test/foo/bla/rest".toPath
    val instanceId = Instance.Id.forRunSpec(appId)
    val taskId = Task.Id.forInstanceId(instanceId, None)
    taskId.idString should be(instanceId.idString + ".container")
  }

  test("InstanceIds can be converted to TaskIds with container name") {
    val appId = "/test/foo/bla/rest".toPath
    val instanceId = Instance.Id.forRunSpec(appId)
    val container = MesosContainer("firstOne", resources = Resources())
    val taskId = Task.Id.forInstanceId(instanceId, Some(container))
    taskId.idString should be(instanceId.idString + ".firstOne")
  }

  test("InstanceIds should be created from (current) mesos executorID") {
    val appId = "/test/foo/bla/rest".toPath
    val instanceId = Instance.Id.forRunSpec(appId)
    val executorId: mesos.Protos.ExecutorID = mesos.Protos.ExecutorID.newBuilder().setValue(instanceId.executorIdString).build()
    val instanceIdFromExecutorId = Instance.Id(executorId)
    executorId.getValue should startWith ("instance-")
    instanceId should be (instanceIdFromExecutorId)
  }

  test("InstanceIds should be created from (legacy) mesos executorID") {
    val appId = "/test/foo/bla/rest".toPath
    val taskId = Task.Id.forRunSpec(appId)
    val executorId: mesos.Protos.ExecutorID = mesos.Protos.ExecutorID.newBuilder().setValue(Task.Id.executorIdString(taskId.idString)).build()
    val instanceIdFromExecutorId = Instance.Id(executorId)
    executorId.getValue should startWith ("marathon-")
    taskId.instanceId should be (instanceIdFromExecutorId)
  }

}
