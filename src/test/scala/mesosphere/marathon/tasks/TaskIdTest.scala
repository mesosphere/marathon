package mesosphere.marathon
package tasks

import com.fasterxml.uuid.Generators
import mesosphere.UnitTest
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId._
import org.apache.mesos.Protos.TaskID
import org.scalatest.Inside

class TaskIdTest extends UnitTest with Inside {
  "TaskIds" should {
    "AppIds can be converted to TaskIds and back to AppIds" in {
      val appId = "/test/foo/bla/rest".toPath
      val instanceId = Instance.Id.forRunSpec(appId)
      val taskId = Task.Id(instanceId)
      taskId.runSpecId should equal(appId)
    }

    "Old TaskIds can be converted" in {
      val taskId = Task.Id.parse(TaskID.newBuilder().setValue("app_682ebe64-0771-11e4-b05d-e0f84720c54e").build)
      taskId.runSpecId should equal("app".toRootPath)
    }

    "Old TaskIds can be converted even if they have dots in them" in {
      val taskId = Task.Id.parse(TaskID.newBuilder().setValue("app.foo.bar_682ebe64-0771-11e4-b05d-e0f84720c54e").build)
      taskId.runSpecId should equal("app.foo.bar".toRootPath)
    }

    "Old TaskIds can be converted even if they have underscores in them" in {
      val taskId = Task.Id.parse(TaskID.newBuilder().setValue("app_foo_bar_682ebe64-0771-11e4-b05d-e0f84720c54e").build)
      taskId.runSpecId should equal("/app/foo/bar".toRootPath)
    }

    "TaskIds with encoded InstanceIds could be encoded" in {
      val taskId = Task.Id.parse(TaskID.newBuilder().setValue("test_foo_bla_rest.instance-62d0f03f-79aa-11e6-a1a0-660c139c5e15.$anon").build)
      taskId.runSpecId should equal("/test/foo/bla/rest".toRootPath)
      taskId.instanceId.idString should equal("test_foo_bla_rest.instance-62d0f03f-79aa-11e6-a1a0-660c139c5e15")
    }

    "TaskIds with encoded InstanceIds could be encoded even with crucial path ids" in {
      val taskId = Task.Id.parse(TaskID.newBuilder().setValue("test_foo.instance-_bla_rest.instance-62d0f03f-79aa-11e6-a1a0-660c139c5e15.$anon").build)
      taskId.runSpecId should equal("/test/foo.instance-/bla/rest".toRootPath)
      taskId.instanceId.idString should equal("test_foo.instance-_bla_rest.instance-62d0f03f-79aa-11e6-a1a0-660c139c5e15")
    }

    "TaskIds without specific instanceId should use taskId as instanceId" in {
      val taskId = Task.Id.parse(TaskID.newBuilder().setValue("test_foo_bla_rest.62d0f03f-79aa-11e6-a1a0-660c139c5e15").build)
      taskId.runSpecId should equal("/test/foo/bla/rest".toRootPath)
      taskId.instanceId.idString should equal("test_foo_bla_rest.marathon-62d0f03f-79aa-11e6-a1a0-660c139c5e15")
    }

    "TaskIds for resident tasks can be created from legacy taskIds" in {
      val uuidGenerator = Generators.timeBasedGenerator()
      val originalId = Task.LegacyId(PathId("/app"), "-", uuidGenerator.generate())

      val newTaskId = Task.Id.nextIncarnationFor(originalId)
      // this is considered the first attempt
      newTaskId shouldBe a[Task.LegacyResidentId]
      inside(newTaskId) {
        case Task.LegacyResidentId(_, _, _, attempt) =>
          attempt shouldBe 1
      }

      originalId shouldNot equal(newTaskId)
      originalId.instanceId shouldEqual newTaskId.instanceId
    }

    "TaskIds for resident tasks can be incremented" in {
      val taskIdString = "app.test.23.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.41"
      val originalId = Task.Id.parse(taskIdString)
      originalId shouldBe a[Task.LegacyResidentId]
      inside(originalId) {
        case Task.LegacyResidentId(_, _, _, attempt) =>
          attempt should be(41)
      }

      val newTaskId = Task.Id.nextIncarnationFor(originalId)
      newTaskId shouldBe a[Task.LegacyResidentId]
      inside(newTaskId) {
        case Task.LegacyResidentId(_, _, _, attempt) =>
          attempt shouldBe 42
      }

      originalId shouldNot equal(newTaskId)
      originalId.instanceId shouldEqual newTaskId.instanceId
    }

    "TaskId.reservationId is the same as task id when task id is without attempt counter" in {
      val uuidGenerator = Generators.timeBasedGenerator()
      val originalId = Task.LegacyId(PathId("/app"), "-", uuidGenerator.generate())
      val reservationId = originalId.reservationId

      // This is only true for legacy ids. For new ids the task id includes the container while reservation ids do not.
      reservationId.label shouldEqual originalId.idString
    }

    "TaskId.reservationId removes attempt from app task id" in {
      val instanceId = Instance.Id.forRunSpec(PathId("/app/test/23"))
      val originalId = Task.Id(instanceId)

      val residentTaskId = Task.Id.nextIncarnationFor(originalId)
      residentTaskId.instanceId shouldEqual originalId.instanceId
      residentTaskId.reservationId shouldEqual originalId.reservationId

      val anotherResidentTaskId = Task.Id.nextIncarnationFor(residentTaskId)
      anotherResidentTaskId.instanceId shouldEqual originalId.instanceId
      anotherResidentTaskId.reservationId shouldEqual originalId.reservationId
    }

    "TaskId.reservationId removes attempt and container name from pod task id" in {
      val originalId = Task.Id(Instance.Id.forRunSpec(PathId("/app/test/23")))

      val residentTaskId = Task.Id.nextIncarnationFor(originalId)
      residentTaskId.instanceId shouldEqual originalId.instanceId

      val anotherResidentTaskId = Task.Id.nextIncarnationFor(residentTaskId)
      anotherResidentTaskId.instanceId shouldEqual originalId.instanceId

      anotherResidentTaskId.reservationId shouldEqual residentTaskId.reservationId
    }

    "TaskId.reservationId works as expected for all types of task ids" in {
      val appTaskId = Task.Id.parse("app.4455cb85-0c16-490d-b84e-481f8321ff0a")
      appTaskId shouldBe a[Task.LegacyId]
      appTaskId.reservationId.label shouldEqual "app.4455cb85-0c16-490d-b84e-481f8321ff0a"

      val appResidentTaskIdWithAttempt = Task.Id.parse("app.4455cb85-0c16-490d-b84e-481f8321ff0a.1")
      appResidentTaskIdWithAttempt shouldBe a[Task.LegacyResidentId]
      appResidentTaskIdWithAttempt.reservationId.label shouldEqual "app.4455cb85-0c16-490d-b84e-481f8321ff0a"

      val podTaskIdWithContainerName = Task.Id.parse("app.instance-4455cb85-0c16-490d-b84e-481f8321ff0a.ct")
      podTaskIdWithContainerName shouldBe a[Task.EphemeralTaskId]
      podTaskIdWithContainerName.reservationId.label shouldEqual "app.instance-4455cb85-0c16-490d-b84e-481f8321ff0a"

      val podTaskIdWithContainerNameAndAttempt = Task.Id.parse("app.instance-4455cb85-0c16-490d-b84e-481f8321ff0a.ct.1")
      podTaskIdWithContainerNameAndAttempt shouldBe a[Task.TaskIdWithIncarnation]
      podTaskIdWithContainerNameAndAttempt.reservationId.label shouldEqual "app.instance-4455cb85-0c16-490d-b84e-481f8321ff0a"
    }

    "TaskId with incarnation can be parsed from idString" in {
      val originalId = Task.Id(Instance.Id.forRunSpec(PathId("/app/test/23")), None)

      val idString = originalId.idString

      Task.Id.parse(idString) should be(originalId)
    }
  }
}
