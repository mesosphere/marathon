package mesosphere.marathon.tasks

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.{ MarathonConf, MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.protos.Implicits.{ slaveIDToProto, taskIDToProto }
import mesosphere.mesos.protos.{ SlaveID, TaskID }
import org.mockito.Mockito._

class DefaultTaskFactoryTest extends MarathonSpec {

  test("Copy SlaveID from Offer to Task") {

    val offer = MarathonTestHelper.makeBasicOffer()
      .setHostname("some_host")
      .setSlaveId(SlaveID("some slave ID"))
      .build()
    val appDefinition: AppDefinition = AppDefinition(ports = List())
    val runningTasks: Set[Task] = Set(
      MarathonTestHelper.mininimalTask("some task ID")
    )

    when(taskIdUtil.newTaskId(appDefinition.id)).thenReturn(TaskID("some task ID"))

    val createdTask = taskFactory.newTask(appDefinition, offer, runningTasks).get

    val expectedTask =
      Task(
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
    assert(createdTask.task == expectedTask)
  }

  var taskIdUtil: TaskIdUtil = _
  var taskTracker: TaskTracker = _
  var config: MarathonConf = _
  var taskFactory: DefaultTaskFactory = _
  var clock: Clock = _

  before {
    clock = ConstantClock()
    taskIdUtil = mock[TaskIdUtil]
    taskTracker = mock[TaskTracker]
    config = MarathonTestHelper.defaultConfig()
    taskFactory = new DefaultTaskFactory(taskIdUtil, config, clock)
  }

}
