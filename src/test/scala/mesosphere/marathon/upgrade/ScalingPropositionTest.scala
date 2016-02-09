package mesosphere.marathon.upgrade

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.Timestamp
import org.scalatest.{ FunSuite, Matchers }

class ScalingPropositionTest extends FunSuite with Matchers {

  test("propose - empty tasksToKill should lead to ScalingProposition(None, _)") {
    val proposition = ScalingProposition.propose(
      runningTasks = emptyTaskSet,
      toKill = Some(emptyTaskSet),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 0
    )

    proposition.tasksToKill shouldBe empty
  }

  test("propose - nonEmpty tasksToKill should be ScalingProposition(Some(_), _)") {
    val task = MarathonTask.getDefaultInstance
    val proposition = ScalingProposition.propose(
      runningTasks = Set(task),
      toKill = Some(Set(task)),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 0
    )

    proposition.tasksToKill shouldEqual Some(Seq(task))
  }

  test("propose - scaleTo = 0 should be ScalingProposition(_, None)") {
    val proposition = ScalingProposition.propose(
      runningTasks = emptyTaskSet,
      toKill = Some(emptyTaskSet),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 0
    )

    proposition.tasksToStart shouldBe empty
  }

  test("propose - negative scaleTo should be ScalingProposition(_, None)") {
    val proposition = ScalingProposition.propose(
      runningTasks = emptyTaskSet,
      toKill = Some(emptyTaskSet),
      meetConstraints = noConstraintsToMeet,
      scaleTo = -42
    )

    proposition.tasksToStart shouldBe empty
  }

  test("propose - positive scaleTo should be ScalingProposition(_, Some(_))") {
    val proposition = ScalingProposition.propose(
      runningTasks = emptyTaskSet,
      toKill = Some(emptyTaskSet),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 42
    )

    proposition.tasksToStart shouldBe Some(42)
  }

  test("Determine tasks to kill and start when none are sentenced and need to scale") {
    val proposition = ScalingProposition.propose(
      runningTasks = Set(createTask(1), createTask(2), createTask(3)).map(_.marathonTask),
      toKill = Some(emptyTaskSet),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 5
    )

    proposition.tasksToKill shouldBe empty
    proposition.tasksToStart shouldBe Some(2)
  }

  test("Determine tasks to kill when scaling to 0") {
    val runningTasks = Set(createTask(1), createTask(2), createTask(3))
    val proposition = ScalingProposition.propose(
      runningTasks = runningTasks.map(_.marathonTask),
      toKill = Some(emptyTaskSet),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 0
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual runningTasks.toSeq.reverse.map(_.marathonTask)
    proposition.tasksToStart shouldBe empty
  }

  test("Determine tasks to kill w/ invalid task") {
    val task_1 = createTask(1)
    val task_2 = createTask(2)
    val task_3 = createTask(3)
    val alreadyKilled = createTask(42)

    val proposition = ScalingProposition.propose(
      runningTasks = Set(task_1, task_2, task_3).map(_.marathonTask),
      toKill = Some(Set(task_2, task_3, alreadyKilled).map(_.marathonTask)),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 3
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual Seq(task_2, task_3).map(_.marathonTask)
    proposition.tasksToStart shouldBe Some(2)
  }

  test("Determine tasks to kill w/ invalid task 2") {
    val task_1 = createTask(1)
    val task_2 = createTask(2)
    val task_3 = createTask(3)
    val task_4 = createTask(4)
    val alreadyKilled = createTask(42)

    val proposition = ScalingProposition.propose(
      runningTasks = Set(task_1, task_2, task_3, task_4).map(_.marathonTask),
      toKill = Some(Set(alreadyKilled.marathonTask)),
      meetConstraints = noConstraintsToMeet,
      scaleTo = 3
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual Seq(task_4).map(_.marathonTask)
    proposition.tasksToStart shouldBe empty
  }

  test("Determine tasks to kill w/ sentenced, constraints and scaling") {
    val task_1 = createTask(1)
    val task_2 = createTask(2)
    val task_3 = createTask(3)
    val task_4 = createTask(4)

    val proposition = ScalingProposition.propose(
      runningTasks = Set(task_1, task_2, task_3, task_4).map(_.marathonTask),
      toKill = Some(Set(task_2.marathonTask)),
      meetConstraints = killToMeetConstraints(task_3.marathonTask),
      scaleTo = 1
    )

    proposition.tasksToKill shouldBe defined
    proposition.tasksToKill.get shouldEqual Seq(task_2, task_3, task_4).map(_.marathonTask)
    proposition.tasksToStart shouldBe empty
  }

  // Helper functions

  private def createTask(index: Long) = MarathonTestHelper.runningTask(s"task-$index", appVersion = Timestamp(index), startedAt = Timestamp.now().toDateTime.getMillis)

  private def noConstraintsToMeet(running: Iterable[MarathonTask], killCount: Int) = Iterable.empty[MarathonTask]

  private def killToMeetConstraints(tasks: MarathonTask*): (Iterable[MarathonTask], Int) => Iterable[MarathonTask] =
    (running: Iterable[MarathonTask], killCount: Int) => tasks

  private def emptyTaskSet = Set.empty[MarathonTask]

}
