package mesosphere.marathon.upgrade

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.MarathonTaskStatus
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
    val task = MarathonTestHelper.stagedTaskForApp()
    val proposition = ScalingProposition.propose(
      runningTasks = Iterable(task),
      toKill = Some(Iterable(task)),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 0
    )

    proposition.tasksToKill shouldEqual Some(Seq(task))
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
      runningTasks = Iterable(createTask(1), createTask(2), createTask(3)),
      toKill = Some(noTasks),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 5
    )

    proposition.tasksToKill shouldBe empty
    proposition.tasksToStart shouldBe Some(2)
  }

  test("Determine tasks to kill when scaling to 0") {
    val runningTasks = Iterable(createTask(1), createTask(2), createTask(3))
    val proposition = ScalingProposition.propose(
      runningTasks = runningTasks,
      toKill = Some(noTasks),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 0
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual runningTasks.toSeq.reverse
    proposition.tasksToStart shouldBe empty
  }

  test("Determine tasks to kill w/ invalid task") {
    val task_1 = createTask(1)
    val task_2 = createTask(2)
    val task_3 = createTask(3)
    val alreadyKilled = createTask(42)

    val proposition = ScalingProposition.propose(
      runningTasks = Iterable(task_1, task_2, task_3),
      toKill = Some(Iterable(task_2, task_3, alreadyKilled)),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 3
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual Seq(task_2, task_3)
    proposition.tasksToStart shouldBe Some(2)
  }

  test("Determine tasks to kill w/ invalid task 2") {
    val task_1 = createTask(1)
    val task_2 = createTask(2)
    val task_3 = createTask(3)
    val task_4 = createTask(4)
    val alreadyKilled = createTask(42)

    val proposition = ScalingProposition.propose(
      runningTasks = Iterable(task_1, task_2, task_3, task_4),
      toKill = Some(Iterable(alreadyKilled)),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 3
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual Seq(task_4)
    proposition.tasksToStart shouldBe empty
  }

  test("Determine tasks to kill w/ sentenced, constraints and scaling") {
    val task_1 = createTask(1)
    val task_2 = createTask(2)
    val task_3 = createTask(3)
    val task_4 = createTask(4)

    val proposition = ScalingProposition.propose(
      runningTasks = Iterable(task_1, task_2, task_3, task_4),
      toKill = Some(Iterable(task_2)),
      meetConstraints = killToMeetConstraints(task_3),
      scaleTo = 1
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual Seq(task_2, task_3, task_4)
    proposition.tasksToStart shouldBe empty
  }

  test("Order of tasks to kill: kill LOST and unhealthy before running, staging, healthy") {
    val runningTask = createTask(1)
    val lostTask = createLostTask(2)
    val stagingTask = createStagingTask(3)

    val proposition = ScalingProposition.propose(
      runningTasks = Iterable(runningTask, lostTask, stagingTask),
      toKill = None,
      meetConstraints = killToMeetConstraints(),
      scaleTo = 1
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get should have size 2
    proposition.tasksToKill.get shouldEqual Seq(lostTask, stagingTask)
    proposition.tasksToStart shouldBe empty
  }

  test("Order of tasks to kill: running and lost") {
    val runningTask = createTask(2)
    val lostTask = createLostTask(1)

    val proposition = ScalingProposition.propose(
      runningTasks = Iterable(runningTask, lostTask),
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
    val runningTask = createTask(2)
    val lostTask = createLostTask(1)

    val proposition = ScalingProposition.propose(
      runningTasks = Iterable(lostTask, runningTask),
      toKill = None,
      meetConstraints = killToMeetConstraints(),
      scaleTo = 1
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get should have size 1
    proposition.tasksToKill.get shouldEqual Seq(lostTask)
    proposition.tasksToStart shouldBe empty
  }

  // Helper functions

  private def createTask(index: Long) = MarathonTestHelper.runningTask(s"task-$index", appVersion = Timestamp(index), startedAt = Timestamp.now().+(index.hours).toDateTime.getMillis)

  private def createLostTask(index: Long): Task.LaunchedEphemeral = MarathonTestHelper.minimalUnreachableTask(PathId("/test"), MarathonTaskStatus.Unreachable)

  private def createStagingTask(index: Long) = MarathonTestHelper.stagedTask(s"task-$index")

  private def noConstraintsToMeet(running: Iterable[Task], killCount: Int) = Iterable.empty[Task]

  private def killToMeetConstraints(tasks: Task*): (Iterable[Task], Int) => Iterable[Task] =
    (running: Iterable[Task], killCount: Int) => tasks

  private def noTasks = Iterable.empty[Task]

}
