package mesosphere.marathon.tasks

import mesosphere.marathon.core.task.Task
import org.apache.mesos.Protos.TaskID
import org.scalatest.{ Matchers, FunSuite }
import mesosphere.marathon.state.PathId._

class TaskIdTest extends FunSuite with Matchers {

  test("AppIds can be converted to TaskIds and back to AppIds") {
    val appId = "/test/foo/bla/rest".toPath
    val taskId = Task.Id.forApp(appId)
    taskId.appId should equal(appId)
  }

  test("Old TaskIds can be converted") {
    val taskId = Task.Id(TaskID.newBuilder().setValue("app_682ebe64-0771-11e4-b05d-e0f84720c54e").build)
    taskId.appId should equal("app".toRootPath)
  }

  test("Old TaskIds can be converted even if they have dots in them") {
    val taskId = Task.Id(TaskID.newBuilder().setValue("app.foo.bar_682ebe64-0771-11e4-b05d-e0f84720c54e").build)
    taskId.appId should equal("app.foo.bar".toRootPath)
  }

  test("Old TaskIds can be converted even if they have underscores in them") {
    val taskId = Task.Id(TaskID.newBuilder().setValue("app_foo_bar_0-12345678").build)
    taskId.appId should equal("/app/foo/bar".toRootPath)
  }
}
