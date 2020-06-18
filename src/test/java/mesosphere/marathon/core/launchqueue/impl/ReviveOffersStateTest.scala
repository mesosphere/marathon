package mesosphere.marathon
package core.launchqueue.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.update.InstancesSnapshot
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersStreamLogic.VersionedRoleState
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition}
import org.scalatest.Inside

class ReviveOffersStateTest extends UnitTest with Inside {

  val webApp = AppDefinition(id = AbsolutePathId("/test"), role = "web")
  val monitoringApp = AppDefinition(id = AbsolutePathId("/test2"), role = "monitoring")

  "register the existence of roles for already-running instances" in {
    val webRunningInstance = TestInstanceBuilder.newBuilderForRunSpec(webApp).addTaskRunning().instance
    val monitoringScheduledInstance = Instance.scheduled(monitoringApp)

    val state =
      ReviveOffersState.empty.withSnapshot(InstancesSnapshot(List(webRunningInstance, monitoringScheduledInstance)), defaultRole = "*")

    state.roleReviveVersions("web").roleState shouldBe OffersNotWanted
    state.roleReviveVersions("monitoring").roleState shouldBe OffersWanted
  }

  "register the existence of the default role" in {
    val state = ReviveOffersState.empty.withSnapshot(InstancesSnapshot(Nil), defaultRole = "*")

    state.roleReviveVersions("*").roleState shouldBe OffersNotWanted
  }

  "bumps the version for a role when a delay is removed" in {
    val monitoringScheduledInstance = Instance.scheduled(monitoringApp)

    var state = ReviveOffersState.empty
      .withSnapshot(InstancesSnapshot(List(monitoringScheduledInstance)), defaultRole = "*")

    val priorVersion = inside(state.roleReviveVersions("monitoring")) {
      case VersionedRoleState(version, roleState) =>
        roleState shouldBe OffersWanted
        version
    }

    state = state.withDelay(monitoringApp.configRef)

    state.roleReviveVersions("monitoring").roleState shouldBe OffersNotWanted

    state = state.withoutDelay(monitoringApp.configRef)

    inside(state.roleReviveVersions("monitoring")) {
      case VersionedRoleState(version, roleState) =>
        roleState shouldBe OffersWanted
        version should be > priorVersion
    }
  }
}
