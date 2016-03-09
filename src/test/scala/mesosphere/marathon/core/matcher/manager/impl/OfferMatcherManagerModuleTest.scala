package mesosphere.marathon.core.matcher.manager.impl

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.TaskOp
import mesosphere.marathon.core.launcher.impl.TaskOpFactoryHelper
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ MatchedTaskOps, TaskOpSource, TaskOpWithSource }
import mesosphere.marathon.core.matcher.manager.{ OfferMatcherManagerConfig, OfferMatcherManagerModule }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.tasks.ResourceUtil
import mesosphere.marathon.test.MarathonShutdownHookSupport
import org.apache.mesos.Protos.{ Offer, TaskInfo }
import org.scalatest.{ Matchers, BeforeAndAfter, FunSuite }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.Random
import scala.collection.JavaConverters._

class OfferMatcherManagerModuleTest extends FunSuite with BeforeAndAfter with MarathonShutdownHookSupport with Matchers {

  // FIXME: Missing Tests
  // Adding matcher while matching offers
  // Removing matcher while matching offers, removed matcher does not get offers anymore
  // Timeout for matching
  // Deal with randomness?

  test("no registered matchers result in empty result") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer().build()
    val matchedTasksFuture: Future[MatchedTaskOps] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedTaskOps = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.opsWithSource.isEmpty)
  }

  test("single offer is passed to matcher") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0).build()

    val task = makeOneCPUTask("task1")
    val matcher: CPUOfferMatcher = new CPUOfferMatcher(Seq(task))
    module.subOfferMatcherManager.setLaunchTokens(10)
    module.subOfferMatcherManager.addSubscription(matcher)

    val matchedTasksFuture: Future[MatchedTaskOps] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedTaskOps = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.offerId == offer.getId)
    assert(matchedTasks.launchedTaskInfos == Seq(makeOneCPUTask("task1_1")))
  }

  test("deregistering only matcher works") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0).build()

    val task = makeOneCPUTask("task1")
    val matcher: CPUOfferMatcher = new CPUOfferMatcher(Seq(task))
    module.subOfferMatcherManager.setLaunchTokens(10)
    module.subOfferMatcherManager.addSubscription(matcher)
    module.subOfferMatcherManager.removeSubscription(matcher)

    val matchedTasksFuture: Future[MatchedTaskOps] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedTaskOps = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.opsWithSource.isEmpty)
  }

  test("single offer is passed to multiple matchers") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0).build()

    module.subOfferMatcherManager.setLaunchTokens(10)

    val task1: TaskInfo = makeOneCPUTask("task1")
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task1)))
    val task2: TaskInfo = makeOneCPUTask("task2")
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task2)))

    val matchedTasksFuture: Future[MatchedTaskOps] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedTaskOps = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.launchedTaskInfos.toSet == Set(makeOneCPUTask("task1_1"), makeOneCPUTask("task2_1")))
  }

  for (launchTokens <- Seq(0, 1, 5)) {
    test(s"launch as many tasks as there are launch tokens: $launchTokens") {
      val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 1.3).build()

      module.subOfferMatcherManager.setLaunchTokens(launchTokens)

      val task1: TaskInfo = makeOneCPUTask("task1")
      module.subOfferMatcherManager.addSubscription(new ConstantOfferMatcher(Seq(task1)))

      val matchedTasksFuture: Future[MatchedTaskOps] =
        module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
      val matchedTasks: MatchedTaskOps = Await.result(matchedTasksFuture, 3.seconds)
      assert(matchedTasks.opsWithSource.size == launchTokens)
    }
  }

  test("single offer is passed to multiple matchers repeatedly") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 4.0).build()

    module.subOfferMatcherManager.setLaunchTokens(10)

    val task1: TaskInfo = makeOneCPUTask("task1")
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task1)))
    val task2: TaskInfo = makeOneCPUTask("task2")
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task2)))

    val matchedTasksFuture: Future[MatchedTaskOps] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedTaskOps = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.launchedTaskInfos.toSet == Set(
      makeOneCPUTask("task1_1"),
      makeOneCPUTask("task1_2"),
      makeOneCPUTask("task2_1"),
      makeOneCPUTask("task2_2")
    ))
  }

  test("ports of an offer should be displayed in a short notation if they exceed a certain quantity") {
    //scalastyle:off magic.number
    val offer: Offer = MarathonTestHelper.makeBasicOfferWithManyPortRanges(100).build()
    //scalastyle:on magic.number
    val resources = ResourceUtil.displayResources(offer.getResourcesList.asScala, 10)
    resources should include("ports(*) 1->2,3->4,5->6,7->8,9->10,11->12,13->14,15->16,17->18,19->20 ... (90 more)")
  }

  def makeOneCPUTask(idBase: String) = {
    MarathonTestHelper.makeOneCPUTask(idBase).build()
  }

  object f {
    import org.apache.mesos.{ Protos => Mesos }
    val launch = new TaskOpFactoryHelper(Some("principal"), Some("role")).launchEphemeral(_: Mesos.TaskInfo, _: Task.LaunchedEphemeral)
  }

  private[this] var module: OfferMatcherManagerModule = _
  private[this] var clock: Clock = _

  before {
    clock = Clock()
    val random = Random
    val actorSystem = AlwaysElectedLeadershipModule(shutdownHooks)
    val config = new OfferMatcherManagerConfig {}
    config.afterInit()
    module = new OfferMatcherManagerModule(clock, random, new Metrics(new MetricRegistry), config, actorSystem)
  }

  /**
    * Simplistic matcher which always matches the same tasks, even if not enough resources are available.
    */
  private class ConstantOfferMatcher(tasks: Seq[TaskInfo]) extends OfferMatcher {

    var results = Vector.empty[MatchedTaskOps]
    var processCycle = 0
    def numberedTasks() = {
      processCycle += 1
      tasks.map { task =>
        task
          .toBuilder
          .setTaskId(task.getTaskId.toBuilder.setValue(task.getTaskId.getValue + "_" + processCycle))
          .build()
      }
    }

    protected def matchTasks(deadline: Timestamp, offer: Offer): Seq[TaskInfo] = numberedTasks()

    override def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedTaskOps] = {
      val opsWithSources = matchTasks(deadline, offer).map { task =>
        val launch = f.launch(task, MarathonTestHelper.makeTaskFromTaskInfo(task, offer))
        TaskOpWithSource(source, launch)
      }

      val result = MatchedTaskOps(offer.getId, opsWithSources)
      results :+= result
      Future.successful(result)
    }

    object source extends TaskOpSource {
      var acceptedOps = Vector.empty[TaskOp]
      var rejectedOps = Vector.empty[TaskOp]

      override def taskOpAccepted(taskOp: TaskOp): Unit = acceptedOps :+= taskOp
      override def taskOpRejected(taskOp: TaskOp, reason: String): Unit = rejectedOps :+= taskOp
    }
  }

  /**
    * Simplistic matcher which only looks if there are sufficient CPUs in the offer
    * for the given tasks. It has no state and thus continues matching infinitely.
    */
  private class CPUOfferMatcher(tasks: Seq[TaskInfo]) extends ConstantOfferMatcher(tasks) {
    import scala.collection.JavaConverters._

    val totalCpus: Double = {
      val cpuValues = for {
        task <- tasks
        resource <- task.getResourcesList.asScala
        if resource.getName == "cpus"
        cpuScalar <- Option(resource.getScalar)
        cpus = cpuScalar.getValue
      } yield cpus
      cpuValues.sum
    }

    override def matchTasks(deadline: Timestamp, offer: Offer): Seq[TaskInfo] = {
      val cpusInOffer: Double =
        offer.getResourcesList.asScala.find(_.getName == "cpus")
          .flatMap(r => Option(r.getScalar))
          .map(_.getValue)
          .getOrElse(0)

      if (cpusInOffer >= totalCpus) numberedTasks() else Seq.empty
    }
  }
}
