package mesosphere.marathon
package core.group

import javax.inject.Provider
import akka.Done
import akka.event.EventStream
import mesosphere.marathon.core.deployment.DeploymentStepInfo
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.event.GroupChangeSuccess
import mesosphere.marathon.core.group.impl.GroupManagerImpl
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.test.GroupCreation

import scala.concurrent.{ExecutionContext, Future, Promise}

class GroupManagerTest extends AkkaUnitTest with GroupCreation {
  class Fixture(
      val servicePortsRange: Range = 1000.to(20000),
      val initialRoot: Option[RootGroup] = Some(RootGroup.empty()),
      val maxRunningDeployments: Int = 100) {
    val config = AllConf.withTestConfig(
      "--local_port_min", servicePortsRange.min.toString,
      "--local_port_max", (servicePortsRange.max).toString,
      "--max_running_deployments", maxRunningDeployments.toString
    )
    val groupRepository = mock[GroupRepository]
    val deploymentService = mock[DeploymentService]
    deploymentService.listRunningDeployments() returns Future.successful(Seq.empty)
    val metrics = DummyMetrics

    val eventStream = mock[EventStream]
    val groupManager = new GroupManagerImpl(
      metrics, config, initialRoot, groupRepository, new Provider[DeploymentService] {
      override def get(): DeploymentService = deploymentService
    })(eventStream, ExecutionContext.Implicits.global)
  }

  "GroupManager" should {
    "return None as a root group, if the initial group has not been passed to it" in new Fixture(initialRoot = None) {
      groupManager.rootGroupOption() shouldBe None
    }

    "not store invalid groups" in new Fixture {
      val app1 = AppDefinition(AbsolutePathId("/app1"), role = "*")
      val rootGroup = Builders.newRootGroup(apps = Seq(app1))

      groupRepository.root() returns Future.successful(Builders.newRootGroup())

      intercept[ValidationFailedException] {
        throw groupManager.updateRoot(PathId.root, _.putGroup(rootGroup, rootGroup.version), rootGroup.version, force = false).failed.futureValue
      }

      verify(groupRepository, times(0)).storeRoot(any, any, any, any, any)
    }

    "return multiple apps when asked" in {
      val app1 = AppDefinition(AbsolutePathId("/app1"), role = "*", cmd = Some("sleep"))
      val app2 = AppDefinition(AbsolutePathId("/app2"), role = "*", cmd = Some("sleep"))
      val rootGroup = Builders.newRootGroup(apps = Seq(app1, app2))
      val f = new Fixture(initialRoot = Some(rootGroup))

      f.groupManager.apps(Set(app1.id, app2.id)) should be(Map(app1.id -> Some(app1), app2.id -> Some(app2)))
    }

    "publishes GroupChangeSuccess with the appropriate GID on successful deployment" in new Fixture {
      val app: AppDefinition = AppDefinition(AbsolutePathId("/group/app1"), role = "*", cmd = Some("sleep 3"), portDefinitions = Seq.empty)
      val group = createGroup("/group".toAbsolutePath, apps = Map(app.id -> app), version = Timestamp(1))

      groupRepository.root() returns Future.successful(Builders.newRootGroup(version = Timestamp(1)))
      deploymentService.deploy(any, any) returns Future.successful(Done)
      val appWithAdditionalInfo = app.copy(
        versionInfo = VersionInfo.forNewConfig(Timestamp(1)),
        role = "*"
      )

      val groupWithVersionInfo = Builders.newRootGroup(
        version = Timestamp(1),
        apps = Seq(appWithAdditionalInfo))
      groupRepository.storeRootVersion(any, any, any) returns Future.successful(Done)
      groupRepository.storeRoot(any, any, any, any, any) returns Future.successful(Done)
      val groupChangeSuccess = Promise[GroupChangeSuccess]
      eventStream.publish(any).answers {
        case Array(change: GroupChangeSuccess) =>
          groupChangeSuccess.success(change)
        case _ =>
          ???
      }

      groupManager.updateRoot(PathId.root, _.putGroup(group, version = Timestamp(1)), version = Timestamp(1), force = false).futureValue
      verify(groupRepository).storeRoot(groupWithVersionInfo, Seq(appWithAdditionalInfo), Nil, Nil, Nil)
      verify(groupRepository).storeRootVersion(groupWithVersionInfo, Seq(appWithAdditionalInfo), Nil)

      groupChangeSuccess.future.
        futureValue.
        groupId shouldBe PathId.root
    }

    "store new apps with correct version infos in groupRepo and appRepo" in new Fixture {

      val app: AppDefinition = AppDefinition(AbsolutePathId("/app1"), role = "*", cmd = Some("sleep 3"), portDefinitions = Seq.empty)
      val rootGroup = Builders.newRootGroup(apps = Seq(app), version = Timestamp(1))
      groupRepository.root() returns Future.successful(createRootGroup())
      deploymentService.deploy(any, any) returns Future.successful(Done)
      val appWithAdditionalInfo = app.copy(
        versionInfo = VersionInfo.forNewConfig(Timestamp(1)),
        role = "*"
      )

      val groupWithVersionInfo = createRootGroup(Map(
        appWithAdditionalInfo.id -> appWithAdditionalInfo), version = Timestamp(1))
      groupRepository.storeRootVersion(any, any, any) returns Future.successful(Done)
      groupRepository.storeRoot(any, any, any, any, any) returns Future.successful(Done)

      groupManager.updateRoot(PathId.root, _.putGroup(rootGroup, version = Timestamp(1)), version = Timestamp(1), force = false).futureValue

      verify(groupRepository).storeRoot(groupWithVersionInfo, Seq(appWithAdditionalInfo), Nil, Nil, Nil)
      verify(groupRepository).storeRootVersion(groupWithVersionInfo, Seq(appWithAdditionalInfo), Nil)
    }

    "expunge removed apps from appRepo" in new Fixture(initialRoot = Option({
      val app: AppDefinition = AppDefinition(AbsolutePathId("/app1"), role = "*", cmd = Some("sleep 3"), portDefinitions = Seq.empty)
      createRootGroup(Map(app.id -> app), version = Timestamp(1))
    })) {
      val groupEmpty = createRootGroup(version = Timestamp(1))

      deploymentService.deploy(any, any) returns Future.successful(Done)
      groupRepository.storeRootVersion(any, any, any) returns Future.successful(Done)
      groupRepository.storeRoot(any, any, any, any, any) returns Future.successful(Done)

      groupManager.updateRoot(PathId.root, _.putGroup(groupEmpty, version = Timestamp(1)), Timestamp(1), force = false).futureValue
      verify(groupRepository).storeRootVersion(groupEmpty, Nil, Nil)
      verify(groupRepository).storeRoot(groupEmpty, Nil, Seq(AbsolutePathId("/app1")), Nil, Nil)
    }

    "dismiss deployments when max_running_deployments limit is achieved" in new Fixture(maxRunningDeployments = 5) {
      val app1 = AppDefinition(AbsolutePathId("/app1"), role = "*")
      val rootGroup = Builders.newRootGroup(apps = Seq(app1))
      groupRepository.root() returns Future.successful(Builders.newRootGroup())

      val running = 1.to(maxRunningDeployments).map(_ => mock[DeploymentStepInfo])
      deploymentService.listRunningDeployments() returns Future.successful(running)

      intercept[TooManyRunningDeploymentsException] {
        throw groupManager.updateRoot(PathId.root, _.putGroup(rootGroup, rootGroup.version), rootGroup.version, force = false).failed.futureValue
      }

    }
  }
}
