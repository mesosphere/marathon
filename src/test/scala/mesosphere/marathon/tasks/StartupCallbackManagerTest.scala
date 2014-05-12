package mesosphere.marathon.tasks

import mesosphere.marathon.MarathonSpec
import org.apache.mesos.Protos.TaskState

class StartupCallbackManagerTest  extends MarathonSpec {

  test("add") {
    val callbacks = new StartupCallbackManager

    callbacks.add("test", TaskState.TASK_RUNNING, 3) { _ => }

    val entry = callbacks.callbacks.get("test", TaskState.TASK_RUNNING)

    assert(entry.nonEmpty, "Should contain entry for appId 'test'.")
    assert(entry.exists(_._1.get() == 3), "Counter value should be 3.")
  }

  test("countdown") {
    val callbacks = new StartupCallbackManager
    var res = false

    callbacks.add("test", TaskState.TASK_RUNNING, 2) { success =>
      res = success
    }
    callbacks.countdown("test", TaskState.TASK_RUNNING)

    val entry = callbacks.callbacks.get("test", TaskState.TASK_RUNNING)

    assert(entry.exists(_._1.get() == 1), "Counter value should be 1 after countdown.")
    assert(!res, "Should not execute the callback before count is 0.")

    callbacks.countdown("test", TaskState.TASK_RUNNING)

    assert(entry.exists(_._1.get() == 0), "Counter value should be 0 after second countdown.")
    assert(res, "Should execute the callback with 'true' when count hits 0.")

    assert(callbacks.callbacks.get("test", TaskState.TASK_RUNNING).isEmpty, "Callback should be removed after count hits 0.")
  }

  test("remove") {
    val callbacks = new StartupCallbackManager
    var res = true

    callbacks.add("test", TaskState.TASK_RUNNING, 2) { success =>
      res = success
    }
    callbacks.remove("test", TaskState.TASK_RUNNING)

    assert(!res, "Should execute the callback with 'false' when callback is removed.")
  }
}
