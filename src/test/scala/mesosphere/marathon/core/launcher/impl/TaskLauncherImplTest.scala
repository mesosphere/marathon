package mesosphere.marathon
package core.launcher.impl

import java.util
import java.util.Collections

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder._
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.launcher.{ InstanceOp, TaskLauncher }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.OfferID
import org.apache.mesos.Protos.TaskInfo
import org.apache.mesos.{ Protos, SchedulerDriver }
import org.mockito.Mockito
import org.mockito.Mockito.when

class TaskLauncherImplTest extends UnitTest {
  private[this] val offerId = OfferID("offerId")
  private[this] val offerIdAsJava: util.Collection[Protos.OfferID] = Collections.singleton[Protos.OfferID](offerId)
  private[this] def launch(taskInfoBuilder: TaskInfo.Builder): InstanceOp.LaunchTask = {
    val taskInfo = taskInfoBuilder.build()
    val instance = TestInstanceBuilder.newBuilderWithInstanceId(instanceId).addTaskWithBuilder().taskFromTaskInfo(taskInfo).build().getInstance()
    val task: Task.LaunchedEphemeral = instance.appTask
    new InstanceOpFactoryHelper(Some("principal"), Some("role")).launchEphemeral(taskInfo, task, instance)
  }
  private[this] val appId = PathId("/test")
  private[this] val instanceId = Instance.Id.forRunSpec(appId)
  private[this] val launch1 = launch(MarathonTestHelper.makeOneCPUTask(Task.Id.forInstanceId(instanceId, None)))
  private[this] val launch2 = launch(MarathonTestHelper.makeOneCPUTask(Task.Id.forInstanceId(instanceId, None)))
  private[this] val ops = Seq(launch1, launch2)
  private[this] val opsAsJava = ops.flatMap(_.offerOperations).asJava
  private[this] val filter = Protos.Filters.newBuilder().setRefuseSeconds(0).build()

  case class Fixture(driver: Option[SchedulerDriver] = Some(mock[SchedulerDriver])) {
    val driverHolder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    driverHolder.driver = driver
    val launcher: TaskLauncher = new TaskLauncherImpl(driverHolder)

    def verifyClean(): Unit = {
      driverHolder.driver.foreach(Mockito.verifyNoMoreInteractions(_))
    }
  }

  "TaskLauncherImpl" should {
    "launchTasks without driver" in new Fixture(driver = None) {
      assert(!launcher.acceptOffer(offerId, ops))
      verifyClean()
    }

    "unsuccessful launchTasks" in new Fixture {
      when(driverHolder.driver.get.acceptOffers(offerIdAsJava, opsAsJava, filter))
        .thenReturn(Protos.Status.DRIVER_ABORTED)

      assert(!launcher.acceptOffer(offerId, ops))

      verify(driverHolder.driver.get).acceptOffers(offerIdAsJava, opsAsJava, filter)
      verifyClean()
    }

    "successful launchTasks" in new Fixture {
      when(driverHolder.driver.get.acceptOffers(offerIdAsJava, opsAsJava, filter))
        .thenReturn(Protos.Status.DRIVER_RUNNING)

      assert(launcher.acceptOffer(offerId, ops))

      verify(driverHolder.driver.get).acceptOffers(offerIdAsJava, opsAsJava, filter)
      verifyClean()
    }

    "declineOffer without driver" in new Fixture(driver = None) {
      launcher.declineOffer(offerId, refuseMilliseconds = None)
      verifyClean()
    }

    "declineOffer with driver" in new Fixture {
      launcher.declineOffer(offerId, refuseMilliseconds = None)

      verify(driverHolder.driver.get).declineOffer(offerId, Protos.Filters.getDefaultInstance)
      verifyClean()
    }

    "declineOffer with driver and defined refuse seconds" in new Fixture {
      launcher.declineOffer(offerId, Some(123))
      val filter = Protos.Filters.newBuilder().setRefuseSeconds(123 / 1000.0).build()
      verify(driverHolder.driver.get).declineOffer(offerId, filter)
      verifyClean()
    }
  }
}
