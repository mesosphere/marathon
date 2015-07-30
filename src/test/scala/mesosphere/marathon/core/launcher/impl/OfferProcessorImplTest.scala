package mesosphere.marathon.core.launcher.impl

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.launcher.{ OfferProcessor, OfferProcessorConfig, TaskLauncher }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import OfferMatcher.{ MatchedTasks, TaskLaunchSource, TaskWithSource }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.apache.mesos.Protos.TaskInfo
import org.mockito.Mockito.{ verify, when }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class OfferProcessorImplTest extends MarathonSpec {
  private[this] val offer = MarathonTestHelper.makeBasicOffer().build()
  private[this] val offerId = offer.getId
  private[this] val taskInfo1 = MarathonTestHelper.makeOneCPUTask("taskid1").build()
  private[this] val taskInfo2 = MarathonTestHelper.makeOneCPUTask("taskid2").build()
  private[this] val tasks = Seq(taskInfo1, taskInfo2)

  test("match successful, launch tasks successful") {
    val dummySource = new DummySource
    val tasksWithSource = tasks.map(TaskWithSource(dummySource, _))

    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    when(offerMatcher.matchOffer(deadline, offer)).thenReturn(
      Future.successful(MatchedTasks(offerId, tasksWithSource))
    )
    when(taskLauncher.launchTasks(offerId, tasks)).thenReturn(true)

    Await.result(offerProcessor.processOffer(offer), 1.second)

    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).launchTasks(offerId, tasks)

    assert(dummySource.rejected.isEmpty)
    assert(dummySource.accepted.toSeq == tasks)
  }

  test("match successful, launch tasks unsuccessful") {
    val dummySource = new DummySource
    val tasksWithSource = tasks.map(TaskWithSource(dummySource, _))

    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    when(offerMatcher.matchOffer(deadline, offer)).thenReturn(
      Future.successful(MatchedTasks(offerId, tasksWithSource))
    )
    when(taskLauncher.launchTasks(offerId, tasks)).thenReturn(false)

    Await.result(offerProcessor.processOffer(offer), 1.second)

    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).launchTasks(offerId, tasks)

    assert(dummySource.accepted.isEmpty)
    assert(dummySource.rejected.toSeq.map(_._1) == tasks)
  }

  test("match empty => decline") {
    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    when(offerMatcher.matchOffer(deadline, offer)).thenReturn(
      Future.successful(MatchedTasks(offerId, Seq.empty))
    )

    Await.result(offerProcessor.processOffer(offer), 1.second)

    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).declineOffer(offerId)
  }

  test("match crashed => decline") {
    val offerProcessor = createProcessor()

    val deadline: Timestamp = clock.now() + 1.second
    when(offerMatcher.matchOffer(deadline, offer)).thenReturn(
      Future.failed(new RuntimeException("failed matching"))
    )

    Await.result(offerProcessor.processOffer(offer), 1.second)

    verify(offerMatcher).matchOffer(deadline, offer)
    verify(taskLauncher).declineOffer(offerId)
  }

  private[this] var clock: Clock = _
  private[this] var offerMatcher: OfferMatcher = _
  private[this] var taskLauncher: TaskLauncher = _

  private[this] def createProcessor(): OfferProcessor = {
    val conf = new OfferProcessorConfig {}
    conf.afterInit()

    clock = ConstantClock()
    offerMatcher = mock[OfferMatcher]
    taskLauncher = mock[TaskLauncher]

    new OfferProcessorImpl(conf, clock, new Metrics(new MetricRegistry), offerMatcher, taskLauncher)
  }

  class DummySource extends TaskLaunchSource {
    var rejected = Vector.empty[(TaskInfo, String)]
    var accepted = Vector.empty[TaskInfo]

    override def taskLaunchRejected(taskInfo: TaskInfo, reason: String): Unit = rejected :+= taskInfo -> reason
    override def taskLaunchAccepted(taskInfo: TaskInfo): Unit = accepted :+= taskInfo
  }
}
