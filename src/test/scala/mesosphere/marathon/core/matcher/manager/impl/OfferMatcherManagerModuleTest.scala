package mesosphere.marathon
package core.matcher.manager.impl

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.launcher.impl.InstanceOpFactoryHelper
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpSource, InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.core.matcher.base.util.OfferMatcherSpec
import mesosphere.marathon.core.matcher.manager.{ OfferMatcherManagerConfig, OfferMatcherManagerModule }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.stream._
import mesosphere.marathon.tasks.ResourceUtil
import mesosphere.marathon.test.{ MarathonShutdownHookSupport, MarathonTestHelper }
import org.apache.mesos.Protos.{ Offer, TaskInfo }
import org.scalatest.{ BeforeAndAfter, FunSuite, Matchers }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.Random

class OfferMatcherManagerModuleTest extends FunSuite
    with BeforeAndAfter with MarathonShutdownHookSupport with Matchers with OfferMatcherSpec {

  // FIXME: Missing Tests
  // Adding matcher while matching offers
  // Removing matcher while matching offers, removed matcher does not get offers anymore
  // Timeout for matching
  // Deal with randomness?

  test("no registered matchers result in empty result") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer().build()
    val matchedTasksFuture: Future[MatchedInstanceOps] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedInstanceOps = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.opsWithSource.isEmpty)
  }

  test("single offer is passed to matcher") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0).build()

    val task = makeOneCPUTask(Task.Id.forInstanceId(F.instanceId, None))
    val matcher: CPUOfferMatcher = new CPUOfferMatcher(Seq(task))
    module.subOfferMatcherManager.setLaunchTokens(10)
    module.subOfferMatcherManager.addSubscription(matcher)

    val matchedTasksFuture: Future[MatchedInstanceOps] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedInstanceOps = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.offerId == offer.getId)
    assert(launchedTaskInfos(matchedTasks) == Seq(makeOneCPUTask(Task.Id(task.getTaskId.getValue + "-1"))))
  }

  test("deregistering only matcher works") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0).build()

    val task = makeOneCPUTask(Task.Id.forInstanceId(F.instanceId, None))
    val matcher: CPUOfferMatcher = new CPUOfferMatcher(Seq(task))
    module.subOfferMatcherManager.setLaunchTokens(10)
    module.subOfferMatcherManager.addSubscription(matcher)
    module.subOfferMatcherManager.removeSubscription(matcher)

    val matchedTasksFuture: Future[MatchedInstanceOps] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedInstanceOps = Await.result(matchedTasksFuture, 3.seconds)
    assert(matchedTasks.opsWithSource.isEmpty)
  }

  test("single offer is passed to multiple matchers") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0).build()

    module.subOfferMatcherManager.setLaunchTokens(10)

    val task1: TaskInfo = makeOneCPUTask(Task.Id.forInstanceId(F.instanceId, None))
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task1)))
    val task2: TaskInfo = makeOneCPUTask(Task.Id.forInstanceId(F.instanceId, None))
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task2)))

    val matchedTasksFuture: Future[MatchedInstanceOps] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedInstanceOps = Await.result(matchedTasksFuture, 3.seconds)
    assert(launchedTaskInfos(matchedTasks).toSet == Set(
      makeOneCPUTask(Task.Id(task1.getTaskId.getValue + "-1")),
      makeOneCPUTask(Task.Id(task2.getTaskId.getValue + "-1")))
    )
  }

  for (launchTokens <- Seq(0, 1, 5)) {
    test(s"launch as many tasks as there are launch tokens: $launchTokens") {
      val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 1.3).build()

      module.subOfferMatcherManager.setLaunchTokens(launchTokens)

      val task1: TaskInfo = makeOneCPUTask(Task.Id.forInstanceId(F.instanceId, None))
      module.subOfferMatcherManager.addSubscription(new ConstantOfferMatcher(Seq(task1)))

      val matchedTasksFuture: Future[MatchedInstanceOps] =
        module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
      val matchedTasks: MatchedInstanceOps = Await.result(matchedTasksFuture, 3.seconds)
      assert(matchedTasks.opsWithSource.size == launchTokens)
    }
  }

  test("single offer is passed to multiple matchers repeatedly") {
    val offer: Offer = MarathonTestHelper.makeBasicOffer(cpus = 4.0).build()

    module.subOfferMatcherManager.setLaunchTokens(10)

    val task1: TaskInfo = makeOneCPUTask(Task.Id.forInstanceId(F.instanceId, None))
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task1)))
    val task2: TaskInfo = makeOneCPUTask(Task.Id.forInstanceId(F.instanceId, None))
    module.subOfferMatcherManager.addSubscription(new CPUOfferMatcher(Seq(task2)))

    val matchedTasksFuture: Future[MatchedInstanceOps] =
      module.globalOfferMatcher.matchOffer(clock.now() + 1.second, offer)
    val matchedTasks: MatchedInstanceOps = Await.result(matchedTasksFuture, 3.seconds)
    assert(launchedTaskInfos(matchedTasks).toSet == Set(
      makeOneCPUTask(Task.Id(task1.getTaskId.getValue + "-1")),
      makeOneCPUTask(Task.Id(task1.getTaskId.getValue + "-2")),
      makeOneCPUTask(Task.Id(task2.getTaskId.getValue + "-1")),
      makeOneCPUTask(Task.Id(task2.getTaskId.getValue + "-2"))
    ))
  }

  test("ports of an offer should be displayed in a short notation if they exceed a certain quantity") {
    val offer: Offer = MarathonTestHelper.makeBasicOfferWithManyPortRanges(100).build()
    val resources = ResourceUtil.displayResources(offer.getResourcesList.toSeq, 10)
    resources should include("ports(*) 1->2,3->4,5->6,7->8,9->10,11->12,13->14,15->16,17->18,19->20 ... (90 more)")
  }

  protected def makeOneCPUTask(taskId: Task.Id) = {
    MarathonTestHelper.makeOneCPUTask(taskId).build()
  }

  object F {
    import org.apache.mesos.{ Protos => Mesos }
    val runSpecId = PathId("/test")
    val instanceId = Instance.Id.forRunSpec(runSpecId)
    val launch = new InstanceOpFactoryHelper(
      Some("principal"),
      Some("role")).launchEphemeral(_: Mesos.TaskInfo, _: Task.LaunchedEphemeral, _: Instance)
  }

  private[this] var module: OfferMatcherManagerModule = _
  private[this] var clock: Clock = _

  before {
    clock = Clock()
    val random = Random
    val actorSystem = AlwaysElectedLeadershipModule(shutdownHooks)
    val config = new OfferMatcherManagerConfig {
      verify()
    }
    module = new OfferMatcherManagerModule(clock, random, new Metrics(new MetricRegistry), config, actorSystem)
  }

  /**
    * Simplistic matcher which always matches the same tasks, even if not enough resources are available.
    */
  private class ConstantOfferMatcher(tasks: Seq[TaskInfo]) extends OfferMatcher {

    var results = Vector.empty[MatchedInstanceOps]
    var processCycle = 0
    protected def numberedTasks() = {
      processCycle += 1
      tasks.map { task =>
        task
          .toBuilder
          .setTaskId(task.getTaskId.toBuilder.setValue(task.getTaskId.getValue + "-" + processCycle))
          .build()
      }
    }

    protected def matchTasks(deadline: Timestamp, offer: Offer): Seq[TaskInfo] = numberedTasks() // linter:ignore:UnusedParameter

    override def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedInstanceOps] = {
      val opsWithSources = matchTasks(deadline, offer).map { taskInfo =>
        val builder = TestInstanceBuilder.newBuilderWithInstanceId(F.instanceId).addTaskWithBuilder().taskFromTaskInfo(taskInfo, offer).build()
        val task: Task.LaunchedEphemeral = builder.pickFirstTask()
        val launch = F.launch(taskInfo, task.copy(taskId = Task.Id(taskInfo.getTaskId)), builder.getInstance())
        InstanceOpWithSource(Source, launch)
      }(collection.breakOut)

      val result = MatchedInstanceOps(offer.getId, opsWithSources)
      results :+= result
      Future.successful(result)
    }

    object Source extends InstanceOpSource {
      var acceptedOps = Vector.empty[InstanceOp]
      var rejectedOps = Vector.empty[InstanceOp]

      override def instanceOpAccepted(taskOp: InstanceOp): Unit = acceptedOps :+= taskOp
      override def instanceOpRejected(taskOp: InstanceOp, reason: String): Unit = rejectedOps :+= taskOp
    }
  }

  /**
    * Simplistic matcher which only looks if there are sufficient CPUs in the offer
    * for the given tasks. It has no state and thus continues matching infinitely.
    */
  private class CPUOfferMatcher(tasks: Seq[TaskInfo]) extends ConstantOfferMatcher(tasks) {
    val totalCpus: Double = {
      val cpuValues = for {
        task <- tasks
        resource <- task.getResourcesList
        if resource.getName == "cpus"
        cpuScalar <- Option(resource.getScalar)
        cpus = cpuScalar.getValue
      } yield cpus
      cpuValues.sum
    }

    override def matchTasks(deadline: Timestamp, offer: Offer): Seq[TaskInfo] = {
      val cpusInOffer: Double =
        offer.getResourcesList.find(_.getName == "cpus")
          .flatMap(r => Option(r.getScalar))
          .map(_.getValue)
          .getOrElse(0)

      if (cpusInOffer >= totalCpus) numberedTasks() else Seq.empty
    }
  }
}
