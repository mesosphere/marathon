package mesosphere.marathon
package core.task.termination.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.state.PathId
import org.scalatest.prop.TableDrivenPropertyChecks

class KillActionTest extends UnitTest with TableDrivenPropertyChecks {

  val clock = ConstantClock()
  val appId = PathId("/test")

  lazy val localVolumeId = LocalVolumeId(appId, "unwanted-persistent-volume", "uuid1")
  lazy val residentLaunchedInstance: Instance = TestInstanceBuilder.newBuilder(appId).
    addTaskResidentLaunched(localVolumeId).
    getInstance()

  lazy val residentUnreachableInstance: Instance = TestInstanceBuilder.newBuilder(appId).
    addTaskWithBuilder().
    taskResidentUnreachable(localVolumeId).
    build().
    getInstance()

  lazy val unreachableInstance: Instance = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable().getInstance()
  lazy val runningInstance: Instance = TestInstanceBuilder.newBuilder(appId).addTaskLaunched().getInstance()

  "computeKillAction" when {
    Table(
      ("name", "instance", "expected"),
      ("an unreachable reserved instance", residentUnreachableInstance, KillAction.Noop),
      ("a running reserved instance", residentLaunchedInstance, KillAction.IssueKillRequest),
      ("an unreachable ephemeral instance", unreachableInstance, KillAction.ExpungeFromState),
      ("a running ephemeral instance", runningInstance, KillAction.IssueKillRequest)
    ).
      foreach {
        case (name, instance, expected) =>
          s"killing ${name}" should {
            s"result in ${expected}" in {
              KillAction(
                instance.instanceId, instance.tasksMap.keys, Some(instance)).
                shouldBe(expected)
            }
          }
      }
  }
}
