package mesosphere.marathon.core.matcher.manager.impl

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.tasks.MarathonTasks
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonTestHelper }
import mesosphere.marathon.core.base.{ Clock, ShutdownHooks }
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import OfferMatcher.{ TaskLaunchSource, TaskWithSource, MatchedTasks }
import mesosphere.marathon.core.matcher.manager.{ OfferMatcherManagerConfig, OfferMatcherManagerModule }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.{ Offer, TaskInfo }
import org.scalatest.{ BeforeAndAfter, FunSuite }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.Random

class OfferMatcherManagerModuleTest extends FunSuite with BeforeAndAfter {

  // FIXME: Missing Tests
  // Adding matcher while matching offers
  // Removing matcher while matching offers, removed matcher does not get offers anymore
  // Timeout for matching
  // Deal with randomness?

  test("no registered matchers result in empty result") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer().build()
    val matchedTasksFuture: Future[MatchedTasks] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedTasks = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.tasks.isEmpty)
  }

  test("single offer is passed to matcher") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0).build()

    val task = makeOneCPUTask("task1")
    val matcher: CPUOfferMatcher = new CPUOfferMatcher(Seq(task))
    module.subOfferMatcherManager.setLaunchTokens(10)
    module.subOfferMatcherManager.addSubscription(matcher)

    val matchedTasksFuture: Future[MatchedTasks] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedTasks = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.offerId == offer.getId)
    assert(matchedTasks.tasks.map(_.taskInfo) == Seq(makeOneCPUTask("task1_1")))
  }

  test("deregistering only matcher works") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0).build()

    val task = makeOneCPUTask("task1")
    val matcher: CPUOfferMatcher = new CPUOfferMatcher(Seq(task))
    module.subOfferMatcherManager.setLaunchTokens(10)
    module.subOfferMatcherManager.addSubscription(matcher)
    module.subOfferMatcherManager.removeSubscription(matcher)

    val matchedTasksFuture: Future[MatchedTasks] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedTasks = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.tasks.isEmpty)
  }

  test("single offer is passed to multiple matchers") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0).build()

    module.subOfferMatcherManager.setLaunchTokens(10)

    val task1: TaskInfo = makeOneCPUTask("task1")
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task1)))
    val task2: TaskInfo = makeOneCPUTask("task2")
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task2)))

    val matchedTasksFuture: Future[MatchedTasks] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedTasks = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.tasks.map(_.taskInfo).toSet == Set(makeOneCPUTask("task1_1"), makeOneCPUTask("task2_1")))
  }

  for (launchTokens <- Seq(0, 1, 10)) {
    test(s"launch as many tasks as there are launch tokens: $launchTokens") {
      val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 1.3).build()

      module.subOfferMatcherManager.setLaunchTokens(launchTokens)

      val task1: TaskInfo = makeOneCPUTask("task1")
      module.subOfferMatcherManager.addSubscription(new ConstantOfferMatcher(Seq(task1)))

      val matchedTasksFuture: Future[MatchedTasks] =
        module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
      val matchedTasks: MatchedTasks = Await.result(matchedTasksFuture, 3.seconds)
      assert(matchedTasks.tasks.size == launchTokens)
    }
  }

  test("single offer is passed to multiple matchers repeatedly") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 4.0).build()

    module.subOfferMatcherManager.setLaunchTokens(10)

    val task1: TaskInfo = makeOneCPUTask("task1")
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task1)))
    val task2: TaskInfo = makeOneCPUTask("task2")
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task2)))

    val matchedTasksFuture: Future[MatchedTasks] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedTasks = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.tasks.map(_.taskInfo).toSet == Set(
      makeOneCPUTask("task1_1"),
      makeOneCPUTask("task1_2"),
      makeOneCPUTask("task2_1"),
      makeOneCPUTask("task2_2")
    ))
  }

  def makeOneCPUTask(idBase: String) = {
    MarathonTestHelper.makeOneCPUTask(idBase).build()
  }

  private[this] var module: OfferMatcherManagerModule = _
  private[this] var shutdownHookModule: ShutdownHooks = _
  private[this] var clock: Clock = _

  before {
    shutdownHookModule = ShutdownHooks()
    clock = Clock()
    val random = Random
    val actorSystem = AlwaysElectedLeadershipModule(shutdownHookModule)
    val config = new OfferMatcherManagerConfig {}
    config.afterInit()
    module = new OfferMatcherManagerModule(clock, random, new Metrics(new MetricRegistry), config, actorSystem)
  }

  after {
    shutdownHookModule.shutdown()
  }

  /**
    * Simplistic matcher which always matches the same tasks, even if not enough resources are available.
    */
  private class ConstantOfferMatcher(tasks: Seq[TaskInfo]) extends OfferMatcher {

    var results = Vector.empty[MatchedTasks]
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

    override def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedTasks] = {
      val result = MatchedTasks(offer.getId, matchTasks(deadline, offer).map(task =>
        TaskWithSource(source, task, MarathonTestHelper.makeTaskFromTaskInfo(task, offer))))
      results :+= result
      Future.successful(result)
    }

    object source extends TaskLaunchSource {
      var acceptedTasks = Vector.empty[TaskInfo]
      var rejectedTasks = Vector.empty[TaskInfo]

      override def taskLaunchAccepted(taskInfo: TaskInfo): Unit = acceptedTasks :+= taskInfo
      override def taskLaunchRejected(taskInfo: TaskInfo, reason: String): Unit = rejectedTasks :+= taskInfo
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
