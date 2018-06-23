package mesosphere.marathon
package core.instance

import java.time.Clock

import mesosphere.UnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.condition.Condition._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.UnreachableDisabled
import mesosphere.marathon.test.SettableClock
import org.apache.mesos.Protos.Attribute
import org.apache.mesos.Protos.Value.{Text, Type}
import org.scalatest.prop.TableDrivenPropertyChecks
import play.api.libs.json._

class InstanceTest extends UnitTest with TableDrivenPropertyChecks {

  "The instance condition" when {

    val stateChangeCases = Table(
      ("to", "withTasks"),
      (Created, Seq(Created, Created, Created)),
      (Staging, Seq(Created, Created, Staging)),
      (Staging, Seq(Running, Staging, Running)),
      (Running, Seq(Running, Finished, Running)),
      (Failed, Seq(Staging, Starting, Running, Killing, Finished, Failed)),
      (Killing, Seq(Staging, Starting, Running, Killing, Finished)),
      (Error, Seq(Staging, Starting, Running, Killing, Finished, Failed, Error)),
      (Staging, Seq(Staging)),
      (Gone, Seq(Gone, Running, Running)),
      (Killed, Seq(Killed, Killed, Killed)),
      (Killing, Seq(Running, Killing, Killed)),
      (Gone, Seq(Running, Gone, Dropped)),
      (Dropped, Seq(Unreachable, Dropped)),
      (Unreachable, Seq(Unreachable, Running))
    )

    forAll (stateChangeCases) { (to, withTasks) =>
      val f = new Fixture

      val (instance, _) = f.instanceWith(withTasks)

      s" tasks become ${withTasks.mkString(", ")}" should {

        s"change to $to" in {
          instance.summarizedTaskStatus(f.clock.now()) should be(to)
        }
      }
    }
  }

  "be reserved" in {
    val f = new Fixture

    val (instance, _) = f.instanceWith(Seq(Condition.Reserved))
    instance.isReserved should be(true)
  }

  "be killing" in {
    val f = new Fixture

    val (instance, _) = f.instanceWith(Seq(Condition.Killing))
    instance.isKilling should be(true)
  }

  "be running" in {
    val f = new Fixture

    val (instance, _) = f.instanceWith(Seq(Condition.Running))
    instance.isRunning should be(true)
  }

  "be unreachable" in {
    val f = new Fixture

    val (instance, _) = f.instanceWith(Seq(Condition.Unreachable))
    instance.isUnreachable(f.clock.now()) should be(true)
  }

  "be unreachable inactive" in {
    val f = new Fixture

    val instance = TestInstanceBuilder.newBuilder(f.id).addTaskUnreachableInactive(f.clock.now()).instance
    instance.isUnreachableInactive(Clock.systemUTC().now()) should be(true)
  }

  "be active only for active conditions" in {
    val f = new Fixture

    val activeConditions = Seq(Created, Killing, Running, Staging, Starting, Unreachable)
    activeConditions.foreach { condition =>
      val (instance, _) = f.instanceWith(Seq(condition))
      println(s"instance ${instance.unreachableStrategy} andtasks ${instance.tasksMap}")
      instance.isActive should be(true) withClue (s"'$condition' was supposed to be active but isActive returned false")
    }

    Condition.all.filterNot(activeConditions.contains(_)).foreach { condition =>
      val (instance, _) = f.instanceWith(Seq(condition))
      instance.isActive should be(false) withClue (s"'$condition' was supposed to not be active but isActive returned true")
    }
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
        val taskId = Task.Id.forRunSpec(id)
        val mesosStatus = MesosTaskStatusTestHelper.mesosStatus(status, taskId, clock.now())
        val task = TestTaskBuilder.Helper.minimalTask(taskId, clock.now(), mesosStatus, status)
        task.taskId -> task
      }(collection.breakOut)

    def instanceWith(conditions: Seq[Condition]): (Instance, Map[Task.Id, Task]) = {
      val currentTasks = tasks(conditions)
      val newTasks = tasks(conditions)
      val state = Instance.InstanceState(None, currentTasks, clock.now(), UnreachableDisabled)
      val instance = Instance(Instance.Id.forRunSpec(id), agentInfo, state, currentTasks,
        runSpecVersion = clock.now(), UnreachableDisabled, None)
      (instance, newTasks)
    }
  }
}
