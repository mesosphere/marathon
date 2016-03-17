package mesosphere.marathon.core.task.bus.impl

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskUpdate
import mesosphere.marathon.core.task.bus.{ TaskBusModule, TaskStatusUpdateTestHelper }
import mesosphere.marathon.state.PathId
import org.scalatest.{ BeforeAndAfter, FunSuite }

class TaskStatusModuleTest extends FunSuite with BeforeAndAfter {
  var module: TaskBusModule = _
  before {
    module = new TaskBusModule
  }

  test("observable forAll includes all app status updates") {
    var received = List.empty[TaskUpdate]
    module.taskStatusObservables.forAll.foreach(received :+= _)

    TaskStatusUpdateTestHelper.running()
    val aa: TaskUpdate = TaskStatusUpdateTestHelper.running(taskForApp("/a/a")).wrapped
    val ab: TaskUpdate = TaskStatusUpdateTestHelper.running(taskForApp("/a/b")).wrapped
    module.taskStatusEmitter.publish(aa)
    module.taskStatusEmitter.publish(ab)
    assert(received == List(aa, ab))
  }

  test("observable forAll unsubscribe works") {
    var received = List.empty[TaskUpdate]
    val subscription = module.taskStatusObservables.forAll.subscribe(received :+= _)
    subscription.unsubscribe()
    val aa: TaskUpdate = TaskStatusUpdateTestHelper.running(taskForApp("/a/a")).wrapped
    module.taskStatusEmitter.publish(aa)
    assert(received == List.empty)
  }

  test("observable forAppId includes only app status updates") {
    var received = List.empty[TaskUpdate]
    module.taskStatusObservables.forAppId(PathId("/a/a")).foreach(received :+= _)
    val aa: TaskUpdate = TaskStatusUpdateTestHelper.running(taskForApp("/a/a")).wrapped
    val ab: TaskUpdate = TaskStatusUpdateTestHelper.running(taskForApp("/a/b")).wrapped
    module.taskStatusEmitter.publish(aa)
    module.taskStatusEmitter.publish(ab)
    assert(received == List(aa))
  }

  test("observable forAppId unsubscribe works") {
    var received = List.empty[TaskUpdate]
    val subscription = module.taskStatusObservables.forAppId(PathId("/a/a")).subscribe(received :+= _)
    subscription.unsubscribe()
    val aa: TaskUpdate = TaskStatusUpdateTestHelper.running(taskForApp("/a/a")).wrapped
    module.taskStatusEmitter.publish(aa)
    assert(received == List.empty)
  }

  private[this] def taskForApp(appId: String) = MarathonTestHelper.stagedTask(Task.Id.forApp(PathId(appId)).idString)

}

