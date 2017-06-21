package mesosphere.marathon
package tasks

import mesosphere.UnitTest
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId._
import org.apache.mesos.Protos.TaskID

class TaskIdTest extends UnitTest {
  "TaskIds" should {
    "AppIds can be converted to TaskIds and back to AppIds" in {
      val appId = "/test/foo/bla/rest".toPath
      val taskId = Task.Id.forRunSpec(appId)
      taskId.runSpecId should equal(appId)
    }

    "Old TaskIds can be converted" in {
      val taskId = Task.Id(TaskID.newBuilder().setValue("app_682ebe64-0771-11e4-b05d-e0f84720c54e").build)
      taskId.runSpecId should equal("app".toRootPath)
    }

    "Old TaskIds can be converted even if they have dots in them" in {
      val taskId = Task.Id(TaskID.newBuilder().setValue("app.foo.bar_682ebe64-0771-11e4-b05d-e0f84720c54e").build)
      taskId.runSpecId should equal("app.foo.bar".toRootPath)
    }

    "Old TaskIds can be converted even if they have underscores in them" in {
      val taskId = Task.Id(TaskID.newBuilder().setValue("app_foo_bar_0-12345678").build)
      taskId.runSpecId should equal("/app/foo/bar".toRootPath)
    }

    "TaskIds with encoded InstanceIds could be encoded" in {
      val taskId = Task.Id(TaskID.newBuilder().setValue("test_foo_bla_rest.instance-instance1.62d0f03f-79aa-11e6-a1a0-660c139c5e15").build)
      taskId.runSpecId should equal("/test/foo/bla/rest".toRootPath)
      taskId.instanceId.idString should equal("test_foo_bla_rest.instance-instance1")
    }

    "TaskIds with encoded InstanceIds could be encoded even with crucial path ids" in {
      val taskId = Task.Id(TaskID.newBuilder().setValue("test_foo.instance-_bla_rest.instance-instance1.62d0f03f-79aa-11e6-a1a0-660c139c5e15").build)
      taskId.runSpecId should equal("/test/foo.instance-/bla/rest".toRootPath)
      taskId.instanceId.idString should equal("test_foo.instance-_bla_rest.instance-instance1")
    }

    "TaskIds without specific instanceId should use taskId as instanceId" in {
      val taskId = Task.Id(TaskID.newBuilder().setValue("test_foo_bla_rest.62d0f03f-79aa-11e6-a1a0-660c139c5e15").build)
      taskId.runSpecId should equal("/test/foo/bla/rest".toRootPath)
      taskId.instanceId.idString should equal("test_foo_bla_rest.marathon-62d0f03f-79aa-11e6-a1a0-660c139c5e15")
    }
  }
}