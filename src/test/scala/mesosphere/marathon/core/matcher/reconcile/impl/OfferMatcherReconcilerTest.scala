package mesosphere.marathon.core.matcher.reconcile.impl

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.launcher.TaskOp
import mesosphere.marathon.core.task.{ TaskStateOp, Task }
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.TaskTracker.TasksByApp
import mesosphere.marathon.state._
import mesosphere.marathon.test.Mockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.concurrent.Future
import scala.concurrent.duration._

class OfferMatcherReconcilerTest extends FunSuite with GivenWhenThen with Mockito with Matchers with ScalaFutures {
  import scala.collection.JavaConverters._

  test("offer without reservations leads to no task ops") {
    val f = new Fixture
    Given("an offer without reservations")
    val offer = MarathonTestHelper.makeBasicOffer().build()
    When("reconciling")
    val matchedTaskOps = f.reconciler.matchOffer(Timestamp.now() + 1.day, offer).futureValue
    Then("no task ops are generated")
    matchedTaskOps.ops should be(empty)
  }

  test("offer with volume for unknown tasks/apps leads to unreserve/destroy") {
    val f = new Fixture
    Given("an offer with volume")
    val appId = PathId("/test")
    val taskId = Task.Id.forApp(appId)
    val localVolumeIdLaunched = LocalVolumeId(appId, "persistent-volume-launched", "uuidLaunched")
    val offer = MarathonTestHelper.offerWithVolumes(taskId.idString, localVolumeIdLaunched)

    And("no groups")
    f.groupRepository.rootGroupOrEmpty() returns Future.successful(Group.empty)
    And("no tasks")
    f.taskTracker.tasksByApp()(any) returns Future.successful(TasksByApp.empty)

    When("reconciling")
    val matchedTaskOps = f.reconciler.matchOffer(Timestamp.now() + 1.day, offer).futureValue

    Then("all resources are destroyed and unreserved")
    val expectedOps =
      Iterable(
        TaskOp.UnreserveAndDestroyVolumes(
          TaskStateOp.ForceExpunge(taskId),
          oldTask = None,
          resources = offer.getResourcesList.asScala.to[Seq]
        )
      )

    // for the nicer error message with diff indication
    matchedTaskOps.ops.mkString("\n") should be(expectedOps.mkString("\n"))
    matchedTaskOps.ops should be(expectedOps)
  }

  test("offer with volume for unknown tasks leads to unreserve/destroy") {
    val f = new Fixture
    Given("an offer with volume")
    val appId = PathId("/test")
    val taskId = Task.Id.forApp(appId)
    val localVolumeIdLaunched = LocalVolumeId(appId, "persistent-volume-launched", "uuidLaunched")
    val offer = MarathonTestHelper.offerWithVolumes(taskId.idString, localVolumeIdLaunched)

    And("a bogus app")
    val app = AppDefinition(appId)
    f.groupRepository.rootGroupOrEmpty() returns Future.successful(Group.empty.copy(apps = Set(app)))
    And("no tasks")
    f.taskTracker.tasksByApp()(any) returns Future.successful(TasksByApp.empty)

    When("reconciling")
    val matchedTaskOps = f.reconciler.matchOffer(Timestamp.now() + 1.day, offer).futureValue

    Then("all resources are destroyed and unreserved")
    val expectedOps = Iterable(
      TaskOp.UnreserveAndDestroyVolumes(
        TaskStateOp.ForceExpunge(taskId),
        oldTask = None,
        resources = offer.getResourcesList.asScala.to[Seq]
      )
    )

    // for the nicer error message with diff
    matchedTaskOps.ops.mkString("\n") should be(expectedOps.mkString("\n"))
    matchedTaskOps.ops should be(expectedOps)
  }

  test("offer with volume for unknown apps leads to unreserve/destroy") {
    val f = new Fixture
    Given("an offer with volume")
    val appId = PathId("/test")
    val taskId = Task.Id.forApp(appId)
    val localVolumeIdLaunched = LocalVolumeId(appId, "persistent-volume-launched", "uuidLaunched")
    val offer = MarathonTestHelper.offerWithVolumes(taskId.idString, localVolumeIdLaunched)

    And("no groups")
    f.groupRepository.rootGroupOrEmpty() returns Future.successful(Group.empty)
    And("a matching bogus task")
    val bogusTask = MarathonTestHelper.mininimalTask(taskId.idString)
    f.taskTracker.tasksByApp()(any) returns Future.successful(TasksByApp.forTasks(bogusTask))

    When("reconciling")
    val matchedTaskOps = f.reconciler.matchOffer(Timestamp.now() + 1.day, offer).futureValue

    Then("all resources are destroyed and unreserved")
    val expectedOps = Iterable(
      TaskOp.UnreserveAndDestroyVolumes(
        TaskStateOp.ForceExpunge(taskId),
        oldTask = Some(bogusTask),
        resources = offer.getResourcesList.asScala.to[Seq]
      )
    )

    // for the nicer error message with diff
    matchedTaskOps.ops.mkString("\n") should be(expectedOps.mkString("\n"))
    matchedTaskOps.ops should be(expectedOps)
  }

  test("offer with volume for known tasks/apps DOES NOT lead to unreserve/destroy") {
    val f = new Fixture
    Given("an offer with volume")
    val appId = PathId("/test")
    val taskId = Task.Id.forApp(appId)
    val localVolumeIdLaunched = LocalVolumeId(appId, "persistent-volume-launched", "uuidLaunched")
    val offer = MarathonTestHelper.offerWithVolumes(taskId.idString, localVolumeIdLaunched)

    And("a matching bogus app")
    val app = AppDefinition(appId)
    f.groupRepository.rootGroupOrEmpty() returns Future.successful(Group.empty.copy(apps = Set(app)))
    And("a matching bogus task")
    f.taskTracker.tasksByApp()(any) returns Future.successful(
      TasksByApp.forTasks(MarathonTestHelper.mininimalTask(taskId.idString))
    )

    When("reconciling")
    val matchedTaskOps = f.reconciler.matchOffer(Timestamp.now() + 1.day, offer).futureValue

    Then("no resources are destroyed and unreserved")
    matchedTaskOps.ops should be(empty)
  }

  class Fixture {
    lazy val taskTracker = mock[TaskTracker]
    lazy val groupRepository = mock[GroupRepository]
    lazy val reconciler = new OfferMatcherReconciler(taskTracker, groupRepository)
  }
}
