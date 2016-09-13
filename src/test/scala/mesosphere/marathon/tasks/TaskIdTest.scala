package mesosphere.marathon.tasks

import mesosphere.marathon.core.task.Task
import org.apache.mesos.Protos.TaskID
import org.scalatest.{ FunSuite, Matchers }
import mesosphere.marathon.state.PathId._

class TaskIdTest extends FunSuite with Matchers {

  test("AppIds can be converted to TaskIds and back to AppIds") {
    val appId = "/test/foo/bla/rest".toPath
    val taskId = Task.Id.forRunSpec(appId)
    taskId.runSpecId should equal(appId)
  }

  test("Old TaskIds can be converted") {
    val taskId = Task.Id(TaskID.newBuilder().setValue("app_682ebe64-0771-11e4-b05d-e0f84720c54e").build)
    taskId.runSpecId should equal("app".toRootPath)
  }

  test("Old TaskIds can be converted even if they have dots in them") {
    val taskId = Task.Id(TaskID.newBuilder().setValue("app.foo.bar_682ebe64-0771-11e4-b05d-e0f84720c54e").build)
    taskId.runSpecId should equal("app.foo.bar".toRootPath)
  }

  test("Old TaskIds can be converted even if they have underscores in them") {
    val taskId = Task.Id(TaskID.newBuilder().setValue("app_foo_bar_0-12345678").build)
    taskId.runSpecId should equal("/app/foo/bar".toRootPath)
  }

  test("TaskIds with encoded InstanceIds could be encoded") {
    val taskId = Task.Id(TaskID.newBuilder().setValue("instance1#test_foo_bla_rest.62d0f03f-79aa-11e6-a1a0-660c139c5e15").build)
    taskId.runSpecId should equal("/test/foo/bla/rest".toRootPath)
    taskId.instanceId.idString should equal("instance1")
  }

  test("TaskIds without specific instanceId should use taskId as instanceId") {
    val taskId = Task.Id(TaskID.newBuilder().setValue("test_foo_bla_rest.62d0f03f-79aa-11e6-a1a0-660c139c5e15").build)
    taskId.runSpecId should equal("/test/foo/bla/rest".toRootPath)
    taskId.instanceId.idString should equal(taskId.idString)
  }
}
