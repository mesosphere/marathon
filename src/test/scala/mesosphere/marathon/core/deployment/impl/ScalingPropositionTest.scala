package mesosphere.marathon
package core.deployment.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment.ScalingProposition
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.state.{AbsolutePathId, KillSelection, Timestamp}

import scala.concurrent.duration._

class ScalingPropositionTest extends UnitTest {

  "ScalingProposition.propose" when {
    "given no running tasks" should {
      val f = new Fixture

      val proposition = ScalingProposition.propose(
        instances = f.noTasks,
        toDecommission = f.noTasks,
        meetConstraints = f.noConstraintsToMeet,
        scaleTo = 0,
        killSelection = KillSelection.DefaultKillSelection,
        f.appId
      )

      "lead to ScalingProposition(None, _)" in {
        proposition.toDecommission shouldBe empty
      }
    }

    "given a staged task to kill" should {
      val f = new Fixture

      val instance = TestInstanceBuilder.newBuilder(f.appId).addTaskStaged().getInstance()
      val proposition = ScalingProposition.propose(
        instances = Seq(instance),
        toDecommission = Seq(instance),
        meetConstraints = f.noConstraintsToMeet,
        scaleTo = 0,
        killSelection = KillSelection.DefaultKillSelection,
        f.appId
      )

      "lead to ScalingProposition list of instances to decommission" in {
        proposition.toDecommission shouldBe Seq(instance)
      }
    }

    "given no tasks and scaleTo = 0" should {
      val f = new Fixture

      val proposition = ScalingProposition.propose(
        instances = f.noTasks,
        toDecommission = f.noTasks,
        meetConstraints = f.noConstraintsToMeet,
        scaleTo = 0,
        killSelection = KillSelection.DefaultKillSelection,
        f.appId
      )

      "lead to ScalingProposition(_, None)" in {
        proposition.toStart shouldBe 0
      }
    }

    "given no tasks and negative scaleTo" should {
      val f = new Fixture

      val proposition = ScalingProposition.propose(
        instances = f.noTasks,
        toDecommission = f.noTasks,
        meetConstraints = f.noConstraintsToMeet,
        scaleTo = -42,
        killSelection = KillSelection.DefaultKillSelection,
        f.appId
      )

      "lead to ScalingProposition(_, None)" in {
        proposition.toStart shouldBe 0
      }
    }

    "given no running tasks and a positive scaleTo" should {
      val f = new Fixture

      val proposition = ScalingProposition.propose(
        instances = f.noTasks,
        toDecommission = f.noTasks,
        meetConstraints = f.noConstraintsToMeet,
        scaleTo = 42,
        killSelection = KillSelection.DefaultKillSelection,
        f.appId
      )

      "lead to ScalePropositionof 42" in {
        proposition.toStart shouldBe 42
      }
    }

    "none are sentenced and need to scale" should {
      val f = new Fixture

      val proposition = ScalingProposition.propose(
        instances = Seq(f.createInstance(1), f.createInstance(2), f.createInstance(3)),
        toDecommission = f.noTasks,
        meetConstraints = f.noConstraintsToMeet,
        scaleTo = 5,
        killSelection = KillSelection.DefaultKillSelection,
        f.appId
      )
      "determine tasks to kill" in {
        proposition.toDecommission shouldBe empty
      }
      "determine tasks to start" in {
        proposition.toStart shouldBe 2
      }
    }

    "scaling to 0" should {
      val f = new Fixture

      val runningTasks: Seq[Instance] = Seq(f.createInstance(1), f.createInstance(2), f.createInstance(3))
      val proposition = ScalingProposition.propose(
        instances = runningTasks,
        toDecommission = f.noTasks,
        meetConstraints = f.noConstraintsToMeet,
        scaleTo = 0,
        killSelection = KillSelection.DefaultKillSelection,
        f.appId
      )

      "determine tasks to kill" in {
        proposition.toDecommission.nonEmpty shouldBe true
        proposition.toDecommission shouldEqual runningTasks.reverse
      }
      "determine no tasks to start" in {
        proposition.toStart shouldBe 0
      }
    }

    "given invalid tasks" should {
      val f = new Fixture

      val task_1: Instance = f.createInstance(1)
      val task_2: Instance = f.createInstance(2)
      val task_3: Instance = f.createInstance(3)
      val alreadyKilled: Instance = f.createInstance(42)

      val proposition = ScalingProposition.propose(
        instances = Seq(task_1, task_2, task_3),
        toDecommission = Seq(task_2, task_3, alreadyKilled),
        meetConstraints = f.noConstraintsToMeet,
        scaleTo = 3,
        killSelection = KillSelection.DefaultKillSelection,
        f.appId
      )

      "determine tasks to kill" in {
        proposition.toDecommission.nonEmpty shouldBe true
        proposition.toDecommission shouldEqual Seq(task_2, task_3)
      }
      "determine tasks to start" in {
        proposition.toStart shouldBe 2
      }
    }

    "given already killed tasks" should {
      val f = new Fixture

      val instance_1 = f.createInstance(1)
      val instance_2 = f.createInstance(2)
      val instance_3 = f.createInstance(3)
      val instance_4 = f.createInstance(4)
      val alreadyKilled = f.createInstance(42)

      val proposition = ScalingProposition.propose(
        instances = Seq(instance_1, instance_2, instance_3, instance_4),
        toDecommission = Seq(alreadyKilled),
        meetConstraints = f.noConstraintsToMeet,
        scaleTo = 3,
        killSelection = KillSelection.DefaultKillSelection,
        f.appId
      )

      "determine tasks to kill" in {
        proposition.toDecommission.nonEmpty shouldBe true
        proposition.toDecommission shouldEqual Seq(instance_4)
      }
      "determine no tasks to start" in {
        proposition.toStart shouldBe 0
      }
    }

    "given sentenced, constraints and scaling" should {
      val f = new Fixture

      val instance_1 = f.createInstance(1)
      val instance_2 = f.createInstance(2)
      val instance_3 = f.createInstance(3)
      val instance_4 = f.createInstance(4)

      val proposition = ScalingProposition.propose(
        instances = Seq(instance_1, instance_2, instance_3, instance_4),
        toDecommission = Seq(instance_2),
        meetConstraints = f.killToMeetConstraints(instance_3),
        scaleTo = 1,
        killSelection = KillSelection.DefaultKillSelection,
        f.appId
      )

      "determine tasks to kill" in {
        proposition.toDecommission.nonEmpty shouldBe true
        proposition.toDecommission shouldEqual Seq(instance_2, instance_3, instance_4)
      }
      "determine no tasks to start" in {
        proposition.toStart shouldBe 0
      }
    }
  }

  "ScalingProposition.sortByConditionAndDate" when {
    "sorting a unreachable, unhealthy, running, staging and healthy tasks" should {
      val f = new Fixture

      val runningInstance = f.createInstance(1)
      val runningInstanceOlder = f.createInstance(0)
      val lostInstance = f.createUnreachableInstance()
      val startingInstance = f.createStartingInstance(Timestamp.now())
      val startingInstanceOlder = f.createStartingInstance(Timestamp.now - 1.hours)
      val stagingInstance = f.createStagingInstance()
      val stagingInstanceOlder = f.createStagingInstance(Timestamp.now - 1.hours)

      "put unreachable before running" in {
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(lostInstance, runningInstance) shouldBe true
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(runningInstance, lostInstance) shouldBe false
      }
      "put unreachable before staging" in {
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(lostInstance, stagingInstance) shouldBe true
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(stagingInstance, lostInstance) shouldBe false
      }
      "put unreachable before starting" in {
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(lostInstance, startingInstance) shouldBe true
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(startingInstance, lostInstance) shouldBe false
      }
      "put staging before starting" in {
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(startingInstance, stagingInstance) shouldBe false
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(startingInstance, runningInstance) shouldBe true
      }
      "put staging before running" in {
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(runningInstance, stagingInstance) shouldBe false
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(stagingInstance, runningInstance) shouldBe true
      }
      "put starting before running" in {
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(runningInstance, startingInstance) shouldBe false
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(startingInstance, runningInstance) shouldBe true
      }
      "put younger staging before older staging" in {
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(stagingInstanceOlder, stagingInstance) shouldBe false
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(stagingInstance, stagingInstanceOlder) shouldBe true
      }
      "put younger starting before older starting" in {
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(startingInstanceOlder, startingInstance) shouldBe false
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(startingInstance, startingInstanceOlder) shouldBe true
      }
      "put younger running before older running " in {
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(runningInstance, runningInstanceOlder) shouldBe true
        ScalingProposition.sortByConditionAndDate(KillSelection.DefaultKillSelection)(runningInstanceOlder, runningInstance) shouldBe false
      }
      "put younger staging before younger staging" in {
        ScalingProposition.sortByConditionAndDate(KillSelection.YoungestFirst)(stagingInstanceOlder, stagingInstance) shouldBe false
        ScalingProposition.sortByConditionAndDate(KillSelection.YoungestFirst)(stagingInstance, stagingInstanceOlder) shouldBe true
      }
      "put younger starting before younger starting" in {
        ScalingProposition.sortByConditionAndDate(KillSelection.YoungestFirst)(startingInstanceOlder, startingInstance) shouldBe false
        ScalingProposition.sortByConditionAndDate(KillSelection.YoungestFirst)(startingInstance, startingInstanceOlder) shouldBe true
      }
      "put younger running before younger running " in {
        ScalingProposition.sortByConditionAndDate(KillSelection.YoungestFirst)(runningInstance, runningInstanceOlder) shouldBe true
        ScalingProposition.sortByConditionAndDate(KillSelection.YoungestFirst)(runningInstanceOlder, runningInstance) shouldBe false
      }
    }
  }

  class Fixture {
    val appId = AbsolutePathId("/test")

    def createInstance(index: Long) = {
      val instance = TestInstanceBuilder.newBuilder(appId, version = Timestamp(index)).addTaskRunning(startedAt = Timestamp.now().+(index.hours)).getInstance()
      val state = instance.state.copy(condition = Condition.Running)
      instance.copy(state = state)
    }

    def createUnreachableInstance(): Instance = {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable().getInstance()
      val state = instance.state.copy(condition = Condition.Unreachable)
      instance.copy(state = state)
    }

    def createStagingInstance(stagedAt: Timestamp = Timestamp.now()) = {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStaged(stagedAt).getInstance()
      val state = instance.state.copy(condition = Condition.Staging)
      instance.copy(state = state)
    }

    def createStartingInstance(since: Timestamp) = {
      val instance = TestInstanceBuilder.newBuilder(appId).addTaskStarting(since).getInstance()
      val state = instance.state.copy(condition = Condition.Starting, since = since)
      instance.copy(state = state)
    }

    def noConstraintsToMeet(running: Seq[Instance], killCount: Int) = // linter:ignore:UnusedParameter
      Seq.empty[Instance]

    def killToMeetConstraints(tasks: Instance*): (Seq[Instance], Int) => Seq[Instance] =
      (running: Seq[Instance], killCount: Int) => tasks.to(Seq)

    def noTasks = Seq.empty[Instance]
  }

}
