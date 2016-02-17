package mesosphere.marathon.tasks

import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.{ MarathonConf, MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.protos.Implicits.slaveIDToProto
import mesosphere.mesos.protos.SlaveID

class DefaultTaskOpLogicTest extends MarathonSpec {

  test("Copy SlaveID from Offer to Task") {

    val offer = MarathonTestHelper.makeBasicOffer()
      .setHostname("some_host")
      .setSlaveId(SlaveID("some slave ID"))
      .build()
    val appDefinition: AppDefinition = AppDefinition(portDefinitions = List())
    val runningTasks: Set[Task] = Set(
      MarathonTestHelper.mininimalTask("some task ID")
    )

    val inferredTaskOp = taskFactory.inferTaskOp(appDefinition, offer, runningTasks)

    val expectedTask = Task(
      taskId = Task.Id("some task ID"),
      agentInfo = Task.AgentInfo(
        host = "some_host",
        agentId = Some(offer.getSlaveId.getValue),
        attributes = List.empty
      ),
      launched = Some(
        Task.Launched(
          appVersion = appDefinition.version,
          status = Task.Status(
            stagedAt = clock.now()
          ),
          networking = Task.HostPorts(List.empty)
        )
      )
    )
    assert(inferredTaskOp.isDefined, "task op is not empty")
    assert(inferredTaskOp.get.newTask.copy(taskId = expectedTask.taskId) == expectedTask)
  }

  var taskTracker: TaskTracker = _
  var config: MarathonConf = _
  var taskFactory: DefaultTaskOpLogic = _
  var clock: Clock = _

  before {
    clock = ConstantClock()
    taskTracker = mock[TaskTracker]
    config = MarathonTestHelper.defaultConfig()
    taskFactory = new DefaultTaskOpLogic(config, clock)
  }

}
