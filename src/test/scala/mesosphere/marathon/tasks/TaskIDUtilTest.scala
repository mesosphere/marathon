package mesosphere.marathon.tasks

import org.apache.mesos.Protos.TaskID
import org.scalatest.{ Matchers, FunSuite }
import mesosphere.marathon.state.PathId._

class TaskIDUtilTest extends FunSuite with Matchers {

  test("AppIds can be converted to TaskIds and back to AppIds") {
    val appId = "/test/foo/bla/rest".toPath
    val taskId = TaskID.newBuilder().setValue(TaskIDUtil.taskId(appId)).build
    TaskIDUtil.appID(taskId) should equal(appId)
  }

  test("Old TaskIds can be converted") {
    val taskId = TaskID.newBuilder().setValue("app_682ebe64-0771-11e4-b05d-e0f84720c54e").build
    TaskIDUtil.appID(taskId) should equal("app".toRootPath)
  }
}
