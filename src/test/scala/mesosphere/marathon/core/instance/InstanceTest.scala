package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.UnreachableStrategy
import org.scalatest.prop.TableDrivenPropertyChecks
import org.apache.mesos.Protos.Attribute
import org.apache.mesos.Protos.Value.{Text, Type}
import play.api.libs.json._

class InstanceTest extends UnitTest with TableDrivenPropertyChecks {

  "The instance condition" when {

    val stateChangeCases = Table(
      ("from", "to", "withTasks"),
      (Provisioned, Provisioned, Seq(Provisioned, Provisioned, Provisioned)),
      (Provisioned, Staging, Seq(Provisioned, Provisioned, Staging)),
      (Staging, Staging, Seq(Running, Staging, Running)),
      (Running, Running, Seq(Running, Finished, Running)),
      (Running, Failed, Seq(Staging, Starting, Running, Killing, Finished, Failed)),
      (Running, Killing, Seq(Staging, Starting, Running, Killing, Finished)),
      (Running, Error, Seq(Staging, Starting, Running, Killing, Finished, Failed, Error)),
      (Staging, Staging, Seq(Staging)),
      (Running, Gone, Seq(Gone, Running, Running)),
      (Killing, Killed, Seq(Killed, Killed, Killed)),
      (Running, Killing, Seq(Running, Killing, Killed)),
      (Running, Gone, Seq(Running, Gone, Dropped)),
      (Running, Dropped, Seq(Unreachable, Dropped))
    )

    forAll (stateChangeCases) { (from, to, withTasks) =>
      val f = new Fixture

      val (instance, tasks) = f.instanceWith(from, withTasks)

      s"$from and tasks become ${withTasks.mkString(", ")}" should {

        val status = Instance.InstanceState(Some(instance.state), tasks, f.clock.now(), UnreachableStrategy.default(), instance.state.goal)

        s"change to $to" in {
          status.condition should be(to)
        }
      }
    }
  }

  "be reserved" in {
    val f = new Fixture

    val (instance, _) = f.instanceWith(Condition.Reserved, Seq(Condition.Reserved))
    instance.isReserved should be(true)
  }

  "be killing" in {
    val f = new Fixture

    val (instance, _) = f.instanceWith(Condition.Killing, Seq(Condition.Killing))
    instance.isKilling should be(true)
  }

  "be running" in {
    val f = new Fixture

    val (instance, _) = f.instanceWith(Condition.Running, Seq(Condition.Running))
    instance.isRunning should be(true)
  }

  "be unreachable" in {
    val f = new Fixture

    val (instance, _) = f.instanceWith(Condition.Unreachable, Seq(Condition.Unreachable))
    instance.isUnreachable should be(true)
  }

  "be unreachable inactive" in {
    val f = new Fixture

    val (instance, _) = f.instanceWith(Condition.UnreachableInactive, Seq(Condition.UnreachableInactive))
    instance.isUnreachableInactive should be(true)
  }

  "be active only for active conditions" in {
    val f = new Fixture

    val activeConditions: Seq[Condition] = Seq(Provisioned, Killing, Running, Staging, Starting, Unreachable)
    activeConditions.foreach { condition =>
      val (instance, _) = f.instanceWith(condition, Seq(condition))
      instance.isActive should be(true)
    }

    Condition.all.filterNot(activeConditions.contains(_)).foreach { condition =>
      val (instance, _) = f.instanceWith(condition, Seq(condition))
      instance.isActive should be(false) withClue (s"'$condition' was supposed to not be active but isActive returned true")
    }
  }

  "say it's reserved when reservation is set" in {
    val f = new Fixture
    val instance = f.instanceWith(Condition.Scheduled, Seq.empty)._1.copy(reservation = Some(Reservation(Seq.empty, Reservation.State.New(None))))
    instance.hasReservation should be (true)
  }

  "agentInfo serialization" should {
    "round trip serialize" in {
      val agentInfo = Instance.AgentInfo(host = "host", agentId = Some("agentId"), region = Some("region"), zone = Some("zone"),
        attributes = Seq(Attribute.newBuilder
          .setName("name")
          .setText(Text.newBuilder.setValue("value"))
          .setType(Type.TEXT)
          .build))
      println(Json.toJson(agentInfo))
      Json.toJson(agentInfo).as[Instance.AgentInfo] shouldBe agentInfo
    }

    "it should default region and zone fields to empty string when missing" in {
      val agentInfo = Json.parse("""{"host": "host", "agentId": "agentId", "attributes": []}""").as[Instance.AgentInfo]
      agentInfo.region shouldBe None
      agentInfo.zone shouldBe None
    }
  }

  class Fixture {
    val id = "/test".toPath
    val clock = new SettableClock()

    val agentInfo = Instance.AgentInfo("", None, None, None, Nil)
    def tasks(statuses: Condition*): Map[Task.Id, Task] = tasks(statuses.to[Seq])
    def tasks(statuses: Seq[Condition]): Map[Task.Id, Task] =
      statuses.map { status =>
        val instanceId = Instance.Id.forRunSpec(id)
        val taskId = Task.Id.forInstanceId(instanceId)
        val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(status, taskId, clock.now())
        val task = TestTaskBuilder.Helper.minimalTask(taskId, clock.now(), mesosStatus, status)
        task.taskId -> task
      }(collection.breakOut)

    def instanceWith(condition: Condition, conditions: Seq[Condition]): (Instance, Map[Task.Id, Task]) = {
      val currentTasks = tasks(conditions.map(_ => condition))
      val newTasks = tasks(conditions)
      val state = Instance.InstanceState(None, currentTasks, clock.now(), UnreachableStrategy.default(), Goal.Running)
      val instance = Instance(Instance.Id.forRunSpec(id), Some(agentInfo), state, currentTasks,
        runSpecVersion = clock.now(), UnreachableStrategy.default(), None)
      (instance, newTasks)
    }
  }
}
