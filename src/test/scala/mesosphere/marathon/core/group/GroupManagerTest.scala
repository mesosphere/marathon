package mesosphere.marathon
package core.group

import javax.inject.Provider

import akka.Done
import akka.event.EventStream
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.event.GroupChangeSuccess
import mesosphere.marathon.core.group.impl.GroupManagerImpl
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork }
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.test.GroupCreation

import scala.concurrent.{ ExecutionContext, Future, Promise }

class GroupManagerTest extends AkkaUnitTest with GroupCreation {
  class Fixture(
      val servicePortsRange: Range = 1000.until(20000),
      val initialRoot: RootGroup = RootGroup.empty) {
    val config = AllConf.withTestConfig("--local_port_min", servicePortsRange.start.toString, "--local_port_max", (servicePortsRange.end + 1).toString)
    val groupRepository = mock[GroupRepository]
    val deploymentService = mock[DeploymentService]
    val storage = mock[StorageProvider]
    val eventStream = mock[EventStream]
    val groupManager = new GroupManagerImpl(config, initialRoot, groupRepository, new Provider[DeploymentService] {
      override def get(): DeploymentService = deploymentService
    }, storage)(eventStream, ExecutionContext.global)
  }
  "applications with port definitions" when {
    "apps with port definitions should map dynamic ports to a non-0 value" in new Fixture(10.to(20)) {
      val app = AppDefinition("/app".toRootPath, portDefinitions = Seq(PortDefinition(0), PortDefinition(1)))
      val rootGroup = createRootGroup(Map(app.id -> app))
      val update = groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)
      update.apps(app.id).portDefinitions.size should equal(2)
      update.apps(app.id).portDefinitions should contain(PortDefinition(1))
      update.apps(app.id).portDefinitions should not contain PortDefinition(0)
    }

    "multiple apps are assigned dynamic app ports" should {
      val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0))
      val app2 = AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(1, 2, 3))
      val app3 = AppDefinition("/app3".toPath, portDefinitions = PortDefinitions(0, 2, 0))
      val rootGroup = createRootGroup(Map(
        app1.id -> app1,
        app2.id -> app2,
        app3.id -> app3
      ))
      "have real ports assigned for all of the original dynamic ports in their definitions" in new Fixture(10.to(20)) {
        val update = groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)
        update.transitiveApps.filter(_.hasDynamicServicePorts) should be(empty)
      }
      "consume service ports from the expected range" in new Fixture(10.to(20)) {
        val update = groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)
        update.transitiveApps.flatMap(_.portNumbers.filter(servicePortsRange.contains)) should have size 5
      }
    }

    //regression for #2743
    "should reassign dynamic service ports specified in the container" in new Fixture(10.to(20)) {
      val app = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 11))
      val updatedApp = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 0, 11))
      val from = createRootGroup(Map(app.id -> app))
      val to = createRootGroup(Map(updatedApp.id -> updatedApp))
      val update = groupManager.assignDynamicServicePorts(from, to)
      update.app("/app1".toPath).get.portNumbers should be(Seq(10, 12, 11))
    }

    "Already taken ports will not be used" in new Fixture(10.to(20)) {
      val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0))
      val app2 = AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(0, 2, 0))
      val rootGroup = createRootGroup(Map(
        app1.id -> app1,
        app2.id -> app2
      ))
      val update = groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)
      update.transitiveApps.filter(_.hasDynamicServicePorts) should be(empty)
      update.transitiveApps.flatMap(_.portNumbers.filter(servicePortsRange.contains)) should have size 5
    }

    // Regression test for #2868
    "Don't assign duplicated service ports" in new Fixture(10.to(20)) {
      val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 10))
      val rootGroup = createRootGroup(Map(app1.id -> app1))
      val update = groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)

      val assignedPorts: Set[Int] = update.transitiveApps.flatMap(_.portNumbers)
      assignedPorts should have size 2
    }

    "Assign unique service ports also when adding a dynamic service port to an app" in new Fixture(10.to(20)) {
      val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 11))
      val originalGroup = createRootGroup(Map(app1.id -> app1))

      val updatedApp1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0))
      val updatedGroup = createRootGroup(Map(updatedApp1.id -> updatedApp1))
      val result = groupManager.assignDynamicServicePorts(originalGroup, updatedGroup)

      val assignedPorts: Set[Int] = result.transitiveApps.flatMap(_.portNumbers)
      assignedPorts should have size 3
    }

    "If there are not enough ports, a PortExhausted exception is thrown" in new Fixture(10.to(14)) {
      val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0))
      val app2 = AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(0, 0, 0))
      val rootGroup = createRootGroup(Map(
        app1.id -> app1,
        app2.id -> app2
      ))
      val ex = intercept[PortRangeExhaustedException] {
        groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)
      }
      ex.minPort should be(10)
      ex.maxPort should be(15)
    }
  }

  def withContainerNetworking(containerization: String, prototype: => Container): Unit = {
    import Container.PortMapping

    s"withContainerNetworking using $containerization" should {
      "Assign dynamic service ports specified in the container" in new Fixture(10.to(14)) {
        val container = prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 0, protocol = "tcp"),
          PortMapping(containerPort = 9000, hostPort = Some(10555), servicePort = 10555, protocol = "udp"),
          PortMapping(containerPort = 9001, hostPort = Some(31337), servicePort = 0, protocol = "udp"),
          PortMapping(containerPort = 9002, hostPort = Some(0), servicePort = 0, protocol = "tcp")
        )
        )
        val virtualNetwork = Seq(ContainerNetwork(name = "whatever"))
        val app = AppDefinition("/app1".toPath, portDefinitions = Seq(), container = Some(container), networks = virtualNetwork)
        val rootGroup = createRootGroup(Map(app.id -> app))
        val updatedGroup = groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)
        val updatedApp = updatedGroup.transitiveApps.head
        updatedApp.hasDynamicServicePorts should be (false)
        updatedApp.hostPorts should have size 4
        updatedApp.servicePorts should have size 4
        updatedApp.servicePorts.filter(servicePortsRange.contains) should have size 3
      }

      "Assign dynamic service ports specified in multiple containers" in new Fixture(10.to(12)) {
        val c1 = Some(prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8080)
        )
        ))
        val c2 = Some(prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8081)
        )
        ))
        val virtualNetwork = Seq(ContainerNetwork(name = "whatever"))
        val app1 = AppDefinition("/app1".toPath, portDefinitions = Seq(), container = c1, networks = virtualNetwork)
        val app2 = AppDefinition("/app2".toPath, portDefinitions = Seq(), container = c2, networks = virtualNetwork)
        val rootGroup = createRootGroup(Map(
          app1.id -> app1,
          app2.id -> app2
        ))
        val update = groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)
        update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
        update.transitiveApps.flatMap(_.hostPorts.flatten.filter(servicePortsRange.contains)) should have size 0 // linter:ignore:AvoidOptionMethod
        update.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 2 // linter:ignore:AvoidOptionMethod
      }

      "Assign dynamic service ports w/ both BRIDGE and USER containers" in new Fixture(0.until(12)) {
        val bridgeModeContainer = Some(prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8080, hostPort = Some(0))
        )
        ))
        val userModeContainer = Some(prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8081),
          PortMapping(containerPort = 8082, hostPort = Some(0))
        )
        ))
        val bridgeModeApp = AppDefinition("/bridgemodeapp".toPath, container = bridgeModeContainer, networks = Seq(BridgeNetwork()))
        val userModeApp = AppDefinition("/usermodeapp".toPath, container = userModeContainer, networks = Seq(ContainerNetwork("whatever")))
        val fromGroup = createRootGroup(Map(bridgeModeApp.id -> bridgeModeApp))
        val toGroup = createRootGroup(Map(
          bridgeModeApp.id -> bridgeModeApp,
          userModeApp.id -> userModeApp
        ))

        val groupsV1 = groupManager.assignDynamicServicePorts(createRootGroup(), fromGroup)
        groupsV1.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
        groupsV1.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 1

        val groupsV2 = groupManager.assignDynamicServicePorts(groupsV1, toGroup)
        groupsV2.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
        val assignedServicePorts = groupsV2.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains))
        assignedServicePorts should have size 3
      }

      "Assign a service port for an app using Docker USER networking with a default port mapping" in new Fixture(10.to(11)) {
        val c1 = Some(prototype.copyWith(portMappings = Seq(
          PortMapping()
        )
        ))
        val app1 = AppDefinition("/app1".toPath, portDefinitions = Seq(), container = c1, networks = Seq(ContainerNetwork("whatever")))
        val rootGroup = createRootGroup(Map(app1.id -> app1))
        val update = groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)
        update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
        update.transitiveApps.flatMap(_.hostPorts.flatten) should have size 0 // linter:ignore:AvoidOptionMethod
        update.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 1 // linter:ignore:AvoidOptionSize
      }

      // Regression test for #1365
      "Export non-dynamic service ports specified in the container to the ports field" in new Fixture(10.to(20)) {
        val container = prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 80, protocol = "tcp"),
          PortMapping(containerPort = 9000, hostPort = Some(10555), servicePort = 81, protocol = "udp")
        )
        )
        val app1 = AppDefinition("/app1".toPath, container = Some(container), networks = Seq(ContainerNetwork("foo")))
        val rootGroup = createRootGroup(Map(app1.id -> app1))
        val update = groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)
        update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
        update.transitiveApps.flatMap(_.servicePorts) should equal (Set(80, 81))
      }

      "Retain the original container definition if port mappings are missing" in new Fixture(10.to(15)) {
        val container: Container = prototype.copyWith(portMappings = Nil)

        val app1 = AppDefinition(
          id = "/app1".toPath,
          container = Some(container)
        )
        val rootGroup = createRootGroup(Map(app1.id -> app1))

        val result = groupManager.assignDynamicServicePorts(createRootGroup(), rootGroup)
        result.apps.size should be(1)
        val app = result.apps.head._2
        app.container should be(Some(container))
      }
    }
  }

  behave like withContainerNetworking("docker-docker", Container.Docker())
  behave like withContainerNetworking("mesos-docker", Container.MesosDocker())
  behave like withContainerNetworking("mesos-appc", Container.MesosAppC())
  behave like withContainerNetworking("mesos", Container.Mesos())

  "GroupManager" should {
    "Don't store invalid groups" in new Fixture {

      val app1 = AppDefinition("/app1".toPath)
      val rootGroup = createRootGroup(Map(app1.id -> app1), groups = Set(createGroup("/app1".toPath)))

      groupRepository.root() returns Future.successful(createRootGroup())

      intercept[ValidationFailedException] {
        throw groupManager.updateRoot(PathId.empty, _.putGroup(rootGroup, rootGroup.version), rootGroup.version, force = false).failed.futureValue
      }

      verify(groupRepository, times(0)).storeRoot(any, any, any, any, any)
    }

    "publishes GroupChangeSuccess with the appropriate GID on successful deployment" in new Fixture {
      val f = new Fixture
      val app: AppDefinition = AppDefinition("/group/app1".toPath, cmd = Some("sleep 3"), portDefinitions = Seq.empty)
      val group = createGroup("/group".toPath, apps = Map(app.id -> app), version = Timestamp(1))
      val rootGroup = createRootGroup(
        version = Timestamp(1),
        groups = Set(group))

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

    "Expunge removed apps from appRepo" in new Fixture(initialRoot = {
      val app: AppDefinition = AppDefinition("/app1".toPath, cmd = Some("sleep 3"), portDefinitions = Seq.empty)
      createRootGroup(Map(app.id -> app), version = Timestamp(1))
    }) {
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
