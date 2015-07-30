package mesosphere.marathon.tasks

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
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
    val version: Timestamp = Timestamp(5)
    val appDefinition: AppDefinition = AppDefinition(ports = List(), version = version)
    val runningTasks: Set[MarathonTask] = Set(MarathonTask.newBuilder().setId("some task ID").build())

    when(taskIdUtil.newTaskId(appDefinition.id)).thenReturn(TaskID("some task ID"))

    val task = taskFactory.newTask(appDefinition, offer, runningTasks).get

    val expectedTask = MarathonTasks.makeTask(
      "some task ID", "some_host", List(),
      List(), version, offer.getSlaveId
    )
    assert(task.marathonTask == expectedTask)
  }

  var taskIdUtil: TaskIdUtil = _
  var taskTracker: TaskTracker = _
  var config: MarathonConf = _
  var taskFactory: DefaultTaskFactory = _

  before {
    taskIdUtil = mock[TaskIdUtil]
    taskTracker = mock[TaskTracker]
    config = MarathonTestHelper.defaultConfig()
    taskFactory = new DefaultTaskFactory(taskIdUtil, config, MarathonTestHelper.getSchemaMapper())
  }

}
