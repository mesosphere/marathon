package mesosphere.marathon.tasks

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId._
import org.apache.mesos.Protos.TaskID
import org.scalatest.{ FunSuite, Matchers }

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
    val taskId = Task.Id(TaskID.newBuilder().setValue("test_foo_bla_rest.instance-instance1.62d0f03f-79aa-11e6-a1a0-660c139c5e15").build)
    taskId.runSpecId should equal("/test/foo/bla/rest".toRootPath)
    taskId.instanceId.idString should equal("test_foo_bla_rest.instance-instance1")
  }

  test("TaskIds with encoded InstanceIds could be encoded even with crucial path ids") {
    val taskId = Task.Id(TaskID.newBuilder().setValue("test_foo.instance-_bla_rest.instance-instance1.62d0f03f-79aa-11e6-a1a0-660c139c5e15").build)
    taskId.runSpecId should equal("/test/foo.instance-/bla/rest".toRootPath)
    taskId.instanceId.idString should equal("test_foo.instance-_bla_rest.instance-instance1")
  }

  test("TaskIds without specific instanceId should use taskId as instanceId") {
    val taskId = Task.Id(TaskID.newBuilder().setValue("test_foo_bla_rest.62d0f03f-79aa-11e6-a1a0-660c139c5e15").build)
    taskId.runSpecId should equal("/test/foo/bla/rest".toRootPath)
    taskId.instanceId.idString should equal("test_foo_bla_rest.marathon-62d0f03f-79aa-11e6-a1a0-660c139c5e15")
  }

  test("container id with hyphens is handled correctly") {
    val taskId = Task.Id.apply(TaskID.newBuilder().setValue("zebra_wookie_thor.instance-c9707703-1eee-11e7-bfcc-70b3d5800004.wookie-thor1").build())
    assert(taskId.instanceId.idString == "zebra_wookie_thor.instance-c9707703-1eee-11e7-bfcc-70b3d5800004")
  }

  test("container id with underscores is handled correctly") {
    val taskId = Task.Id.apply(TaskID.newBuilder().setValue("zebra_wookie_thor.instance-c9707703-1eee-11e7-bfcc-70b3d5800004.wookie_thor1").build())
    assert(taskId.instanceId.idString == "zebra_wookie_thor.instance-c9707703-1eee-11e7-bfcc-70b3d5800004")
  }

  test("TaskIds for resident tasks can be created from legacy taskIds") {
    val originalId = Task.Id.forRunSpec(PathId("/app"))
    originalId.attempt shouldBe None

    val newTaskId = Task.Id.forResidentTask(originalId)
    // this is considered the first attempt
    newTaskId.attempt shouldBe Some(1)

    originalId shouldNot equal(newTaskId)
    originalId.instanceId shouldEqual newTaskId.instanceId
  }

  test("TaskIds for resident tasks can be incremented") {
    val taskIdString = "app.test.23.b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.41"
    val originalId = Task.Id(taskIdString)
    originalId.attempt shouldBe Some(41)

    val newTaskId = Task.Id.forResidentTask(originalId)
    newTaskId.attempt shouldBe Some(42)

    originalId shouldNot equal(newTaskId)
    originalId.instanceId shouldEqual newTaskId.instanceId
  }

  test("TaskId.reservationId returns the same value for an id w/o attempt counter") {
    val originalId = Task.Id.forRunSpec(PathId("/app/test/23"))
    val reservationId = Task.Id.reservationId(originalId.idString)

    reservationId shouldEqual originalId.idString
  }

  test("TaskId.reservationId returns the base value w/o attempt for an id including the attempt") {
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
