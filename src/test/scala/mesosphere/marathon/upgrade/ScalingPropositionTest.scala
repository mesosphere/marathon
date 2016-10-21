package mesosphere.marathon
package upgrade

import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.scalatest.{ FunSuite, Matchers }

import scala.concurrent.duration._

class ScalingPropositionTest extends FunSuite with Matchers {

  test("propose - empty tasksToKill should lead to ScalingProposition(None, _)") {
    val proposition = ScalingProposition.propose(
      runningTasks = noTasks,
      toKill = Some(noTasks),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 0
    )

    proposition.tasksToKill shouldBe empty
  }

  test("propose - nonEmpty tasksToKill should be ScalingProposition(Some(_), _)") {
    val instance = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()
    val proposition = ScalingProposition.propose(
      runningTasks = Seq(instance),
      toKill = Some(Seq(instance)),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 0
    )

    proposition.tasksToKill shouldEqual Some(Seq(instance))
  }

  test("propose - scaleTo = 0 should be ScalingProposition(_, None)") {
    val proposition = ScalingProposition.propose(
      runningTasks = noTasks,
      toKill = Some(noTasks),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 0
    )

    proposition.tasksToStart shouldBe empty
  }

  test("propose - negative scaleTo should be ScalingProposition(_, None)") {
    val proposition = ScalingProposition.propose(
      runningTasks = noTasks,
      toKill = Some(noTasks),
      meetConstraints = noConstraintsToMeet,
      scaleTo = -42
    )

    proposition.tasksToStart shouldBe empty
  }

  test("propose - positive scaleTo should be ScalingProposition(_, Some(_))") {
    val proposition = ScalingProposition.propose(
      runningTasks = noTasks,
      toKill = Some(noTasks),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 42
    )

    proposition.tasksToStart shouldBe Some(42)
  }

  test("Determine tasks to kill and start when none are sentenced and need to scale") {
    val proposition = ScalingProposition.propose(
      runningTasks = Seq(createInstance(1), createInstance(2), createInstance(3)),
      toKill = Some(noTasks),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 5
    )

    proposition.tasksToKill shouldBe empty
    proposition.tasksToStart shouldBe Some(2)
  }

  test("Determine tasks to kill when scaling to 0") {
    val runningTasks: Seq[Instance] = Seq(createInstance(1), createInstance(2), createInstance(3))
    val proposition = ScalingProposition.propose(
      runningTasks = runningTasks,
      toKill = Some(noTasks),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 0
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual runningTasks.reverse
    proposition.tasksToStart shouldBe empty
  }

  test("Determine tasks to kill w/ invalid task") {
    val task_1: Instance = createInstance(1)
    val task_2: Instance = createInstance(2)
    val task_3: Instance = createInstance(3)
    val alreadyKilled: Instance = createInstance(42)

    val proposition = ScalingProposition.propose(
      runningTasks = Seq(task_1, task_2, task_3),
      toKill = Some(Seq(task_2, task_3, alreadyKilled)),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 3
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual Seq(task_2, task_3)
    proposition.tasksToStart shouldBe Some(2)
  }

  test("Determine tasks to kill w/ invalid task 2") {
    val instance_1 = createInstance(1)
    val instance_2 = createInstance(2)
    val instance_3 = createInstance(3)
    val instance_4 = createInstance(4)
    val alreadyKilled = createInstance(42)

    val proposition = ScalingProposition.propose(
      runningTasks = Seq(instance_1, instance_2, instance_3, instance_4),
      toKill = Some(Seq(alreadyKilled)),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 3
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual Seq(instance_4)
    proposition.tasksToStart shouldBe empty
  }

  test("Determine tasks to kill w/ sentenced, constraints and scaling") {
    val instance_1 = createInstance(1)
    val instance_2 = createInstance(2)
    val instance_3 = createInstance(3)
    val instance_4 = createInstance(4)

    val proposition = ScalingProposition.propose(
      runningTasks = Seq(instance_1, instance_2, instance_3, instance_4),
      toKill = Some(Seq(instance_2)),
      meetConstraints = killToMeetConstraints(instance_3),
      scaleTo = 1
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get.toList shouldEqual List(instance_2, instance_3, instance_4)
    proposition.tasksToStart shouldBe empty
  }

  test("Order of tasks to kill: kill LOST and unhealthy before running, staging, healthy") {
    val runningInstance = createInstance(1)
    val lostInstance = createUnreachableInstance()
    val stagingInstance = createStagingInstance()

    val proposition = ScalingProposition.propose(
      runningTasks = Seq(runningInstance, lostInstance, stagingInstance),
      toKill = None,
      meetConstraints = killToMeetConstraints(),
      scaleTo = 1
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get should have size 2
    proposition.tasksToKill.get shouldEqual Seq(lostInstance, stagingInstance)
    proposition.tasksToStart shouldBe empty
  }

  test("Order of tasks to kill: running and lost") {
    val runningTask = createInstance(2)
    val lostTask = createUnreachableInstance()

    val proposition = ScalingProposition.propose(
      runningTasks = Seq(runningTask, lostTask),
      toKill = None,
      meetConstraints = killToMeetConstraints(),
      scaleTo = 1
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get should have size 1
    proposition.tasksToKill.get shouldEqual Seq(lostTask)
    proposition.tasksToStart shouldBe empty
  }

  test("Order of tasks to kill: lost and running") {
    val runningInstance = createInstance(2)
    val lostInstance = createUnreachableInstance()

    val proposition = ScalingProposition.propose(
      runningTasks = Seq(lostInstance, runningInstance),
      toKill = None,
      meetConstraints = killToMeetConstraints(),
      scaleTo = 1
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get should have size 1
    proposition.tasksToKill.get shouldEqual Seq(lostInstance)
    proposition.tasksToStart shouldBe empty
  }

  // Helper functions

  val appId = PathId("/test")

  private def createInstance(index: Long) = TestInstanceBuilder.newBuilder(appId, version = Timestamp(index)).addTaskRunning(startedAt = Timestamp.now().+(index.hours)).getInstance()

  private def createUnreachableInstance(): Instance = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable().getInstance()

  private def createStagingInstance() = TestInstanceBuilder.newBuilder(appId).addTaskStaged().getInstance()

  private def noConstraintsToMeet(running: Seq[Instance], killCount: Int) = // linter:ignore:UnusedParameter
    Seq.empty[Instance]

  private def killToMeetConstraints(tasks: Instance*): (Seq[Instance], Int) => Seq[Instance] =
    (running: Seq[Instance], killCount: Int) => tasks.to[Seq]

  private def noTasks = Seq.empty[Instance]

}
