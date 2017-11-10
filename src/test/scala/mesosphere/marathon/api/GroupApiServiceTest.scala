package mesosphere.marathon
package api

import mesosphere.UnitTest
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork }
import mesosphere.marathon.plugin.auth.Identity
import mesosphere.marathon.raml.{ App, GroupUpdate, Network, NetworkMode }
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import mesosphere.marathon.state.PathId._

import scala.collection.immutable.Seq
import scala.concurrent.Future

class GroupApiServiceTest extends UnitTest with GroupCreation {
  implicit val identity: Identity = new Identity {}

  "revert a version if version is provided" in {
    Given("Group manager with the group version")
    val groupManager = mock[GroupManager]
    val groupId = PathId.empty
    val version = Timestamp.now()
    val groupWithOlderVersion = createGroup(groupId, version = version)
    groupManager.group(groupId, version).returns(Future.successful(Some(groupWithOlderVersion)))
    val f = Fixture(groupManager = groupManager)
    When("Calling update with version provided")
    val updatedGroup = f.groupApiService.updateGroup(
      createRootGroup(),
      PathId.empty,
      GroupUpdate(version = Some(version.toOffsetDateTime)),
      version).futureValue

    Then("Group of the provided version will be returned")
    updatedGroup.group(groupId) should be (Some(groupWithOlderVersion))
  }

  "reverting to non-existing version throws exception" in {
    Given("Group manager with no group of required version")
    val groupManager = mock[GroupManager]
    val groupId = PathId.empty
    val version = Timestamp.now
    groupManager.group(groupId, version).returns(Future.successful(None))
    val f = Fixture(groupManager = groupManager)

    When("Calling update with version provided")
    Then("Exception will be thrown")
    val ex = f.groupApiService.updateGroup(
      createRootGroup(),
      PathId.empty,
      GroupUpdate(version = Some(version.toOffsetDateTime)),
      version).failed.futureValue
    ex shouldBe an[IllegalArgumentException]
  }

  "scale when scaleBy provided" in {
    Given("Initialized service with root group and one app")
    val f = Fixture()
    val app = AppDefinition("/app".toRootPath, cmd = Some("cmd"), networks = Seq(ContainerNetwork("foo")))
    val originalInstancesCount = app.instances
    val rootGroup = createRootGroup(apps = Map(
      "/app".toRootPath -> app
    ))
    When("Calling update with scaleBy")
    val updatedGroup = f.groupApiService.updateGroup(
      rootGroup,
      PathId.empty,
      GroupUpdate(scaleBy = Some(2)),
      Timestamp.now()).futureValue

    Then("Group apps will be scaled by the given amount")
    updatedGroup.apps(app.id).instances should be (originalInstancesCount * 2)
  }

  "update the group if version as well as scaleBy not provided" in {
    Given("Group manager with the group version")
    val groupManager = mock[GroupManager]
    val groupId = PathId.empty
    val newVersion = Timestamp.now()
    val existingGroup = createGroup(groupId, version = newVersion)
    groupManager.group(groupId).returns(Some(existingGroup))
    val f = Fixture(groupManager = groupManager)
    When("Calling update with new apps being added to a group")
    val updatedGroup = f.groupApiService.updateGroup(
      createRootGroup(),
      PathId.empty,
      GroupUpdate(apps = Some(Set(App("/app", networks = Seq(Network(mode = NetworkMode.ContainerBridge)))))),
      newVersion).futureValue

    Then("Group will contain those apps after an update")
    updatedGroup.apps(PathId("/app")) should be (AppDefinition("/app".toRootPath, networks = Seq(BridgeNetwork()), versionInfo = VersionInfo.OnlyVersion(newVersion)))
  }

  case class Fixture(
      authenticated: Boolean = true,
      authorized: Boolean = true,
      authFn: Any => Boolean = _ => true,
      groupManager: GroupManager = mock[GroupManager]) {
    val authFixture = new TestAuthFixture()
    authFixture.authenticated = authenticated
    authFixture.authorized = authorized
    authFixture.authFn = authFn

    val electionService = mock[ElectionService]
    electionService.isLeader returns true

    implicit val authenticator = authFixture.auth
    implicit val ec = mesosphere.marathon.core.async.ExecutionContexts.global

    val groupApiService = new GroupApiService(groupManager)
  }
}
