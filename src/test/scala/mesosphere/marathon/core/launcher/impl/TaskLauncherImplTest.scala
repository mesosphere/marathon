package mesosphere.marathon.core.launcher.impl

import java.util
import java.util.Collections

import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.{ MarathonTestHelper, MarathonSchedulerDriverHolder, MarathonSpec }
import mesosphere.marathon.core.launcher.TaskLauncher
import mesosphere.mesos.protos.OfferID
import org.apache.mesos.Protos.TaskInfo
import org.apache.mesos.{ Protos, SchedulerDriver }
import org.mockito.Mockito
import org.mockito.Mockito.{ when, verify }
import mesosphere.mesos.protos.Implicits._
import scala.collection.JavaConverters._

class TaskLauncherImplTest extends MarathonSpec {
  private[this] val offerId = OfferID("offerId")
  private[this] val offerIdAsJava: util.Set[Protos.OfferID] = Collections.singleton[Protos.OfferID](offerId)
  private[this] val taskInfo1 = MarathonTestHelper.makeOneCPUTask("taskid1").build()
  private[this] val taskInfo2 = MarathonTestHelper.makeOneCPUTask("taskid2").build()
  private[this] val tasks = Seq(taskInfo1, taskInfo2)
  private[this] val tasksAsJava: util.List[TaskInfo] = Seq(taskInfo1, taskInfo2).asJava

  test("launchTasks without driver") {
    driverHolder.driver = None

    assert(!launcher.launchTasks(offerId, Seq(taskInfo1, taskInfo2)))
  }

  test("unsuccessful launchTasks") {
    when(driverHolder.driver.get.launchTasks(offerIdAsJava, tasksAsJava)).thenReturn(Protos.Status.DRIVER_ABORTED)

    assert(!launcher.launchTasks(offerId, Seq(taskInfo1, taskInfo2)))

    verify(driverHolder.driver.get).launchTasks(offerIdAsJava, tasksAsJava)
  }

  test("successful launchTasks") {
    when(driverHolder.driver.get.launchTasks(offerIdAsJava, tasksAsJava)).thenReturn(Protos.Status.DRIVER_RUNNING)

    assert(launcher.launchTasks(offerId, Seq(taskInfo1, taskInfo2)))

    verify(driverHolder.driver.get).launchTasks(offerIdAsJava, tasksAsJava)
  }

  test("declineOffer without driver") {
    driverHolder.driver = None

    launcher.declineOffer(offerId)
  }

  test("declineOffer with driver") {
    launcher.declineOffer(offerId)

    verify(driverHolder.driver.get).declineOffer(offerId)
  }

  var driverHolder: MarathonSchedulerDriverHolder = _
  var launcher: TaskLauncher = _

  before {
    driverHolder = new MarathonSchedulerDriverHolder
    driverHolder.driver = Some(mock[SchedulerDriver])
    launcher = new TaskLauncherImpl(driverHolder, ConstantClock())
  }

  after {
    driverHolder.driver.foreach(Mockito.verifyNoMoreInteractions(_))
  }
}
