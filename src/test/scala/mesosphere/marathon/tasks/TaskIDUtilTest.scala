package mesosphere.marathon.tasks

import org.apache.mesos.Protos.TaskID
import org.scalatest.{ Matchers, FunSuite }
import mesosphere.marathon.state.PathId._

class TaskIDUtilTest extends FunSuite with Matchers {

  val taskIdUtil = new TaskIdUtil()

  test("AppIds can be converted to TaskIds and back to AppIds") {
    val appId = "/test/foo/bla/rest".toPath
    val taskId = TaskID.newBuilder().setValue(taskIdUtil.taskId(appId)).build
    taskIdUtil.appId(taskId) should equal(appId)
  }

  test("Old TaskIds can be converted") {
    val taskId = TaskID.newBuilder().setValue("app_682ebe64-0771-11e4-b05d-e0f84720c54e").build
    taskIdUtil.appId(taskId) should equal("app".toRootPath)
  }

  test("Old TaskIds can be converted even if they have dots in them") {
    val taskId = TaskID.newBuilder().setValue("app.foo.bar_682ebe64-0771-11e4-b05d-e0f84720c54e").build
    taskIdUtil.appId(taskId) should equal("app.foo.bar".toRootPath)
  }

  test("Old TaskIds can be converted even if they have underscores in them") {
    val taskId = TaskID.newBuilder().setValue("app_foo_bar_0-12345678").build
    taskIdUtil.appId(taskId) should equal("/app/foo/bar".toRootPath)
  }
}
