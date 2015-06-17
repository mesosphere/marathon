package mesosphere.marathon.core.task.bus.impl

import mesosphere.marathon.core.task.bus.{ TaskBusModule, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.state.PathId
import org.scalatest.{ BeforeAndAfter, FunSuite }

class TaskStatusModuleTest extends FunSuite with BeforeAndAfter {
  var module: TaskBusModule = _
  before {
    module = new TaskBusModule
  }

  test("observable forAll includes all app status updates") {
    var received = List.empty[TaskStatusUpdate]
    module.taskStatusObservables.forAll.foreach(received :+= _)
    val aa: TaskStatusUpdate = TaskStatusUpdateTestHelper.running.withAppId("/a/a").wrapped
    val ab: TaskStatusUpdate = TaskStatusUpdateTestHelper.running.withAppId("/a/b").wrapped
    module.taskStatusEmitter.publish(aa)
    module.taskStatusEmitter.publish(ab)
    assert(received == List(aa, ab))
  }

  test("observable forAll unsubscribe works") {
    var received = List.empty[TaskStatusUpdate]
    val subscription = module.taskStatusObservables.forAll.subscribe(received :+= _)
    subscription.unsubscribe()
    val aa: TaskStatusUpdate = TaskStatusUpdateTestHelper.running.withAppId("/a/a").wrapped
    module.taskStatusEmitter.publish(aa)
    assert(received == List.empty)
  }

  test("observable forAppId includes only app status updates") {
    var received = List.empty[TaskStatusUpdate]
    module.taskStatusObservables.forAppId(PathId("/a/a")).foreach(received :+= _)
    val aa: TaskStatusUpdate = TaskStatusUpdateTestHelper.running.withAppId("/a/a").wrapped
    val ab: TaskStatusUpdate = TaskStatusUpdateTestHelper.running.withAppId("/a/b").wrapped
    module.taskStatusEmitter.publish(aa)
    module.taskStatusEmitter.publish(ab)
    assert(received == List(aa))
  }

  test("observable forAppId unsubscribe works") {
    var received = List.empty[TaskStatusUpdate]
    val subscription = module.taskStatusObservables.forAppId(PathId("/a/a")).subscribe(received :+= _)
    subscription.unsubscribe()
    val aa: TaskStatusUpdate = TaskStatusUpdateTestHelper.running.withAppId("/a/a").wrapped
    module.taskStatusEmitter.publish(aa)
    assert(received == List.empty)
  }
}
