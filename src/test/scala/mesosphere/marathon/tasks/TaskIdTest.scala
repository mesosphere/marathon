package mesosphere.marathon
package tasks

import mesosphere.UnitTest
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
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

    "TaskIds for resident tasks can be created from legacy taskIds" in {
      val originalId = Task.Id.forRunSpec(PathId("/app"))
      originalId.attempt shouldBe None

      val newTaskId = Task.Id.forResidentTask(originalId)
      // this is considered the first attempt
      newTaskId.attempt shouldBe Some(1)

      originalId shouldNot equal(newTaskId)
      originalId.instanceId shouldEqual newTaskId.instanceId
    }

    "TaskIds for resident tasks can be incremented" in {
      val taskIdString = "app.test.23.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.41"
      val originalId = Task.Id(taskIdString)
      originalId.attempt shouldBe Some(41)

      val newTaskId = Task.Id.forResidentTask(originalId)
      newTaskId.attempt shouldBe Some(42)

      originalId shouldNot equal(newTaskId)
      originalId.instanceId shouldEqual newTaskId.instanceId
    }

    "TaskId.reservationId returns the same value for an id w/o attempt counter" in {
      val originalId = Task.Id.forRunSpec(PathId("/app/test/23"))
      val reservationId = Task.Id.reservationId(originalId.idString)

      reservationId shouldEqual originalId.idString
    }

    "TaskId.reservationId returns the base value w/o attempt for an id including the attempt" in {
      val originalId = Task.Id.forRunSpec(PathId("/app/test/23"))
      val reservationIdFromOriginal = Task.Id.reservationId(originalId.idString)

      val residentTaskId = Task.Id.forResidentTask(originalId)
      residentTaskId.instanceId shouldEqual originalId.instanceId
      Task.Id.reservationId(residentTaskId.idString) shouldEqual reservationIdFromOriginal

      val anotherResidentTaskId = Task.Id.forResidentTask(residentTaskId)
      anotherResidentTaskId.instanceId shouldEqual originalId.instanceId
      Task.Id.reservationId(anotherResidentTaskId.idString) shouldEqual reservationIdFromOriginal
    }
  }
}