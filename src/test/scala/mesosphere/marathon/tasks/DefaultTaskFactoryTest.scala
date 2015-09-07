package mesosphere.marathon.tasks

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
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
    val runningTasks: Set[MarathonTask] = Set(MarathonTask.newBuilder().setId("some task ID").build())

    when(taskIdUtil.newTaskId(appDefinition.id)).thenReturn(TaskID("some task ID"))

    val task = taskFactory.newTask(appDefinition, offer, runningTasks).get

    val expectedTask = MarathonTasks.makeTask(
      id = "some task ID",
      host = "some_host",
      ports = List(),
      attributes = List(),
      version = appDefinition.version,
      now = clock.now(),
      slaveId = offer.getSlaveId
    )
    assert(task.marathonTask == expectedTask)
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
