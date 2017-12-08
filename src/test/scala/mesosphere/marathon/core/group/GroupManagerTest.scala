package mesosphere.marathon
package core.group

import javax.inject.Provider

import akka.Done
import akka.event.EventStream
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.event.GroupChangeSuccess
import mesosphere.marathon.core.group.impl.GroupManagerImpl
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.test.GroupCreation

import scala.concurrent.{ Future, Promise }

class GroupManagerTest extends AkkaUnitTest with GroupCreation {
  class Fixture(
      val servicePortsRange: Range = 1000.to(20000),
      val initialRoot: Option[RootGroup] = Some(RootGroup.empty)) {
    val config = AllConf.withTestConfig("--local_port_min", servicePortsRange.min.toString,
      "--local_port_max", (servicePortsRange.max).toString)
    val groupRepository = mock[GroupRepository]
    val deploymentService = mock[DeploymentService]
    val eventStream = mock[EventStream]
    val groupManager = new GroupManagerImpl(config, initialRoot, groupRepository, new Provider[DeploymentService] {
      override def get(): DeploymentService = deploymentService
    })(eventStream, ExecutionContexts.global)
  }

  "GroupManager" should {
    "return None as a root group, if the initial group has not been passed to it" in new Fixture(initialRoot = None) {
      groupManager.rootGroupOption() shouldBe None
    }

    "Don't store invalid groups" in new Fixture {
      val app1 = AppDefinition("/app1".toPath)
      val rootGroup = createRootGroup(Map(app1.id -> app1), groups = Set(createGroup("/app1".toPath)), validate = false)

      groupRepository.root() returns Future.successful(createRootGroup())

      intercept[ValidationFailedException] {
        throw groupManager.updateRoot(PathId.empty, _.putGroup(rootGroup, rootGroup.version), rootGroup.version, force = false).failed.futureValue
      }

      verify(groupRepository, times(0)).storeRoot(any, any, any, any, any)
    }

    "return multiple apps when asked" in {
      val app1 = AppDefinition("/app1".toPath, cmd = Some("sleep"))
      val app2 = AppDefinition("/app2".toPath, cmd = Some("sleep"))
      val rootGroup = createRootGroup(Map(app1.id -> app1, app2.id -> app2))
      val f = new Fixture(initialRoot = Some(rootGroup))

      f.groupManager.apps(Set(app1.id, app2.id)) should be(Map(app1.id -> Some(app1), app2.id -> Some(app2)))
    }

    "publishes GroupChangeSuccess with the appropriate GID on successful deployment" in new Fixture {
      val app: AppDefinition = AppDefinition("/group/app1".toPath, cmd = Some("sleep 3"), portDefinitions = Seq.empty)
      val group = createGroup("/group".toPath, apps = Map(app.id -> app), version = Timestamp(1))

      groupRepository.root() returns Future.successful(createRootGroup())
      deploymentService.deploy(any, any) returns Future.successful(Done)
      val appWithVersionInfo = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))

      val groupWithVersionInfo = createRootGroup(
        version = Timestamp(1),
        groups = Set(
          createGroup(
            "/group".toPath, apps = Map(appWithVersionInfo.id -> appWithVersionInfo), version = Timestamp(1))))
      groupRepository.storeRootVersion(any, any, any) returns Future.successful(Done)
      groupRepository.storeRoot(any, any, any, any, any) returns Future.successful(Done)
      val groupChangeSuccess = Promise[GroupChangeSuccess]
      eventStream.publish(any).answers {
        case Array(change: GroupChangeSuccess) =>
          groupChangeSuccess.success(change)
        case _ =>
          ???
      }

      groupManager.updateRoot(PathId.empty, _.putGroup(group, version = Timestamp(1)), version = Timestamp(1), force = false).futureValue
      verify(groupRepository).storeRoot(groupWithVersionInfo, Seq(appWithVersionInfo), Nil, Nil, Nil)
      verify(groupRepository).storeRootVersion(groupWithVersionInfo, Seq(appWithVersionInfo), Nil)

      groupChangeSuccess.future.
        futureValue.
        groupId shouldBe PathId.empty
    }

    "Store new apps with correct version infos in groupRepo and appRepo" in new Fixture {

      val app: AppDefinition = AppDefinition("/app1".toPath, cmd = Some("sleep 3"), portDefinitions = Seq.empty)
      val rootGroup = createRootGroup(Map(app.id -> app), version = Timestamp(1))
      groupRepository.root() returns Future.successful(createRootGroup())
      deploymentService.deploy(any, any) returns Future.successful(Done)
      val appWithVersionInfo = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))

      val groupWithVersionInfo = createRootGroup(Map(
        appWithVersionInfo.id -> appWithVersionInfo), version = Timestamp(1))
      groupRepository.storeRootVersion(any, any, any) returns Future.successful(Done)
      groupRepository.storeRoot(any, any, any, any, any) returns Future.successful(Done)

      groupManager.updateRoot(PathId.empty, _.putGroup(rootGroup, version = Timestamp(1)), version = Timestamp(1), force = false).futureValue

      verify(groupRepository).storeRoot(groupWithVersionInfo, Seq(appWithVersionInfo), Nil, Nil, Nil)
      verify(groupRepository).storeRootVersion(groupWithVersionInfo, Seq(appWithVersionInfo), Nil)
    }

    "Expunge removed apps from appRepo" in new Fixture(initialRoot = Option({
      val app: AppDefinition = AppDefinition("/app1".toPath, cmd = Some("sleep 3"), portDefinitions = Seq.empty)
      createRootGroup(Map(app.id -> app), version = Timestamp(1))
    })) {
      val groupEmpty = createRootGroup(version = Timestamp(1))

      deploymentService.deploy(any, any) returns Future.successful(Done)
      groupRepository.storeRootVersion(any, any, any) returns Future.successful(Done)
      groupRepository.storeRoot(any, any, any, any, any) returns Future.successful(Done)

      groupManager.updateRoot(PathId.empty, _.putGroup(groupEmpty, version = Timestamp(1)), Timestamp(1), force = false).futureValue
      verify(groupRepository).storeRootVersion(groupEmpty, Nil, Nil)
      verify(groupRepository).storeRoot(groupEmpty, Nil, Seq("/app1".toPath), Nil, Nil)
    }
  }
}
