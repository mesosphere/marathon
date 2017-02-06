package mesosphere.marathon
package core.task.bus.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.core.task.bus.{ TaskBusModule, TaskStatusUpdateTestHelper }
import mesosphere.marathon.state.PathId

class TaskStatusModuleTest extends UnitTest {

  class Fixture {
    val module = new TaskBusModule
  }

  "TaskStatusModule" should {
    "observable forAll includes all app status updates" in new Fixture {
      var received = List.empty[InstanceChange]
      module.taskStatusObservables.forAll.foreach(received :+= _)

      TaskStatusUpdateTestHelper.running()
      val aa: InstanceChange = TaskStatusUpdateTestHelper.running(instance("/a/a")).wrapped
      val ab: InstanceChange = TaskStatusUpdateTestHelper.running(instance("/a/b")).wrapped
      module.taskStatusEmitter.publish(aa)
      module.taskStatusEmitter.publish(ab)
      assert(received == List(aa, ab))
    }

    "observable forAll unsubscribe works" in new Fixture {
      var received = List.empty[InstanceChange]
      val subscription = module.taskStatusObservables.forAll.subscribe(received :+= _)
      subscription.unsubscribe()
      val aa: InstanceChange = TaskStatusUpdateTestHelper.running(instance("/a/a")).wrapped
      module.taskStatusEmitter.publish(aa)
      assert(received == List.empty)
    }

    "observable forAppId includes only app status updates" in new Fixture {
      var received = List.empty[InstanceChange]
      module.taskStatusObservables.forRunSpecId(PathId("/a/a")).foreach(received :+= _)
      val aa: InstanceChange = TaskStatusUpdateTestHelper.running(instance("/a/a")).wrapped
      val ab: InstanceChange = TaskStatusUpdateTestHelper.running(instance("/a/b")).wrapped
      module.taskStatusEmitter.publish(aa)
      module.taskStatusEmitter.publish(ab)
      assert(received == List(aa))
    }

    "observable forAppId unsubscribe works" in new Fixture {
      var received = List.empty[InstanceChange]
      val subscription = module.taskStatusObservables.forRunSpecId(PathId("/a/a")).subscribe(received :+= _)
      subscription.unsubscribe()
      val aa: InstanceChange = TaskStatusUpdateTestHelper.running(instance("/a/a")).wrapped
      module.taskStatusEmitter.publish(aa)
      assert(received == List.empty)
    }
  }

  private[this] def instance(appId: String) = TestInstanceBuilder.newBuilder(PathId(appId)).addTaskStaged().getInstance()
}

