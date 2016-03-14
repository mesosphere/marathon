package mesosphere.marathon.core.launcher.impl

import java.util
import java.util.Collections

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.launcher.{ TaskOp, TaskLauncher }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.OfferID
import org.apache.mesos.Protos.{ Offer, TaskInfo }
import org.apache.mesos.{ Protos, SchedulerDriver }
import org.mockito.Mockito
import org.mockito.Mockito.{ verify, when }

import scala.collection.JavaConverters._

class TaskLauncherImplTest extends MarathonSpec {
  private[this] val offerId = OfferID("offerId")
  private[this] val offerIdAsJava: util.Set[Protos.OfferID] = Collections.singleton[Protos.OfferID](offerId)
  private[this] def launch(taskInfoBuilder: TaskInfo.Builder): TaskOp.Launch = {
    val taskInfo = taskInfoBuilder.build()
    new TaskOpFactoryHelper(Some("principal"), Some("role")).launchEphemeral(taskInfo, MarathonTestHelper.makeTaskFromTaskInfo(taskInfo))
  }
  private[this] val launch1 = launch(MarathonTestHelper.makeOneCPUTask("task1"))
  private[this] val launch2 = launch(MarathonTestHelper.makeOneCPUTask("task2"))
  private[this] val ops = Seq(launch1, launch2)
  private[this] val opsAsJava: util.List[Offer.Operation] = ops.flatMap(_.offerOperations).asJava
  private[this] val filter = Protos.Filters.newBuilder().setRefuseSeconds(0).build()

  test("launchTasks without driver") {
    driverHolder.driver = None

    assert(!launcher.acceptOffer(offerId, ops))
  }

  test("unsuccessful launchTasks") {
    when(driverHolder.driver.get.acceptOffers(offerIdAsJava, opsAsJava, filter))
      .thenReturn(Protos.Status.DRIVER_ABORTED)

    assert(!launcher.acceptOffer(offerId, ops))

    verify(driverHolder.driver.get).acceptOffers(offerIdAsJava, opsAsJava, filter)
  }

  test("successful launchTasks") {
    when(driverHolder.driver.get.acceptOffers(offerIdAsJava, opsAsJava, filter))
      .thenReturn(Protos.Status.DRIVER_RUNNING)

    assert(launcher.acceptOffer(offerId, ops))

    verify(driverHolder.driver.get).acceptOffers(offerIdAsJava, opsAsJava, filter)
  }

  test("declineOffer without driver") {
    driverHolder.driver = None

    launcher.declineOffer(offerId, refuseMilliseconds = None)
  }

  test("declineOffer with driver") {
    launcher.declineOffer(offerId, refuseMilliseconds = None)

    verify(driverHolder.driver.get).declineOffer(offerId, Protos.Filters.getDefaultInstance)
  }

  test("declineOffer with driver and defined refuse seconds") {
    launcher.declineOffer(offerId, Some(123))
    val filter = Protos.Filters.newBuilder().setRefuseSeconds(123 / 1000.0).build()
    verify(driverHolder.driver.get).declineOffer(offerId, filter)
  }

  var driverHolder: MarathonSchedulerDriverHolder = _
  var launcher: TaskLauncher = _

  before {
    val metrics = new Metrics(new MetricRegistry)
    driverHolder = new MarathonSchedulerDriverHolder
    driverHolder.driver = Some(mock[SchedulerDriver])
    launcher = new TaskLauncherImpl(metrics, driverHolder, ConstantClock())
  }

  after {
    driverHolder.driver.foreach(Mockito.verifyNoMoreInteractions(_))
  }
}
