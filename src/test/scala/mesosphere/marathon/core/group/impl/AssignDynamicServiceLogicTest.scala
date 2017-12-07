package mesosphere.marathon
package core.group.impl

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation

class AssignDynamicServiceLogicTest extends AkkaUnitTest with GroupCreation {
  "applications with port definitions" when {
    "apps with port definitions should map dynamic ports to a non-0 value" in {
      val app = AppDefinition("/app".toRootPath, portDefinitions = Seq(PortDefinition(0), PortDefinition(1)), cmd = Some("sleep"))
      val rootGroup = createRootGroup(Map(app.id -> app))
      val update = AssignDynamicServiceLogic.assignDynamicServicePorts(10.to(20), createRootGroup(), rootGroup)
      update.apps(app.id).portDefinitions.size should equal(2)
      update.apps(app.id).portDefinitions should contain(PortDefinition(1))
      update.apps(app.id).portDefinitions should not contain PortDefinition(0)
    }

    "multiple apps are assigned dynamic app ports" should {
      val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0), cmd = Some("sleep"))
      val app2 = AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(1, 2, 3), cmd = Some("sleep"))
      val app3 = AppDefinition("/app3".toPath, portDefinitions = PortDefinitions(0, 2, 0), cmd = Some("sleep"))
      val rootGroup = createRootGroup(Map(
        app1.id -> app1,
        app2.id -> app2,
        app3.id -> app3
      ))
      "have real ports assigned for all of the original dynamic ports in their definitions" in {
        val update = AssignDynamicServiceLogic.assignDynamicServicePorts(10.to(20), createRootGroup(), rootGroup)
        update.transitiveApps.filter(_.hasDynamicServicePorts) should be(empty)
      }
      "consume service ports from the expected range" in {
        val servicePortsRange = 10.to(20)
        val update = AssignDynamicServiceLogic.assignDynamicServicePorts(servicePortsRange, createRootGroup(), rootGroup)
        update.transitiveApps.flatMap(_.portNumbers.filter(servicePortsRange.contains)) should have size 5
      }
    }

    //regression for #2743
    "should reassign dynamic service ports specified in the container" in {
      val app = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 11), cmd = Some("sleep"))
      val updatedApp = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 0, 11), cmd = Some("sleep"))
      val from = createRootGroup(Map(app.id -> app))
      val to = createRootGroup(Map(updatedApp.id -> updatedApp))
      val update = AssignDynamicServiceLogic.assignDynamicServicePorts(10.to(20), from, to)
      update.app("/app1".toPath).get.portNumbers should be(Seq(10, 12, 11))
    }

    "Already taken ports will not be used" in {
      val servicePortsRange = 10.to(20)
      val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0), cmd = Some("sleep"))
      val app2 = AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(0, 2, 0), cmd = Some("sleep"))
      val rootGroup = createRootGroup(Map(
        app1.id -> app1,
        app2.id -> app2
      ))
      val update = AssignDynamicServiceLogic.assignDynamicServicePorts(servicePortsRange, createRootGroup(), rootGroup)
      update.transitiveApps.filter(_.hasDynamicServicePorts) should be(empty)
      update.transitiveApps.flatMap(_.portNumbers.filter(servicePortsRange.contains)) should have size 5
    }

    // Regression test for #2868
    "Don't assign duplicated service ports" in {
      val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 10), cmd = Some("sleep"))
      val rootGroup = createRootGroup(Map(app1.id -> app1))
      val update = AssignDynamicServiceLogic.assignDynamicServicePorts(10.to(20), createRootGroup(), rootGroup)

      val assignedPorts: Iterable[Int] = update.transitiveApps.flatMap(_.portNumbers)
      assignedPorts should have size 2
    }

    "Assign unique service ports also when adding a dynamic service port to an app" in {
      val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 11), cmd = Some("sleep"))
      val originalGroup = createRootGroup(Map(app1.id -> app1))

      val updatedApp1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0), cmd = Some("sleep"))
      val updatedGroup = createRootGroup(Map(updatedApp1.id -> updatedApp1))
      val result = AssignDynamicServiceLogic.assignDynamicServicePorts(10.to(20), originalGroup, updatedGroup)

      val assignedPorts: Iterable[Int] = result.transitiveApps.flatMap(_.portNumbers)
      assignedPorts should have size 3
    }

    "If there are not enough ports, a PortExhausted exception is thrown" in {
      val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0), cmd = Some("sleep"))
      val app2 = AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(0, 0, 0), cmd = Some("sleep"))
      val rootGroup = createRootGroup(Map(
        app1.id -> app1,
        app2.id -> app2
      ))
      val ex = intercept[PortRangeExhaustedException] {
        AssignDynamicServiceLogic.assignDynamicServicePorts(10.to(14), createRootGroup(), rootGroup)
      }
      ex.minPort should be(10)
      ex.maxPort should be(14)
    }
  }

  def withContainerNetworking(containerization: String, prototype: => Container): Unit = {
    import Container.PortMapping

    s"withContainerNetworking using $containerization" should {
      "Assign dynamic service ports specified in the container" in {
        val servicePortsRange = 10.to(14)
        val container = prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 0, protocol = "tcp"),
          PortMapping(containerPort = 9000, hostPort = Some(10555), servicePort = 10555, protocol = "udp"),
          PortMapping(containerPort = 9001, hostPort = Some(31337), servicePort = 0, protocol = "udp"),
          PortMapping(containerPort = 9002, hostPort = Some(0), servicePort = 0, protocol = "tcp")
        )
        )
        val virtualNetwork = Seq(ContainerNetwork(name = "whatever"))
        val app = AppDefinition("/app1".toPath, portDefinitions = Seq(), container = Some(container), networks = virtualNetwork, cmd = Some("sleep"))
        val rootGroup = createRootGroup(Map(app.id -> app))
        val updatedGroup = AssignDynamicServiceLogic.assignDynamicServicePorts(servicePortsRange, createRootGroup(), rootGroup)
        val updatedApp = updatedGroup.transitiveApps.head
        updatedApp.hasDynamicServicePorts should be (false)
        updatedApp.hostPorts should have size 4
        updatedApp.servicePorts should have size 4
        updatedApp.servicePorts.filter(servicePortsRange.contains) should have size 3
      }

      "Assign dynamic service ports specified in multiple containers" in {
        val c1 = Some(prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8080)
        )
        ))
        val c2 = Some(prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8081)
        )
        ))
        val virtualNetwork = Seq(ContainerNetwork(name = "whatever"))
        val app1 = AppDefinition("/app1".toPath, portDefinitions = Seq(), container = c1, networks = virtualNetwork, cmd = Some("sleep"))
        val app2 = AppDefinition("/app2".toPath, portDefinitions = Seq(), container = c2, networks = virtualNetwork, cmd = Some("sleep"))
        val rootGroup = createRootGroup(Map(
          app1.id -> app1,
          app2.id -> app2
        ))
        val servicePortsRange = 10.to(12)
        val update = AssignDynamicServiceLogic.assignDynamicServicePorts(servicePortsRange, createRootGroup(), rootGroup)
        update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
        update.transitiveApps.flatMap(_.hostPorts.flatten.filter(servicePortsRange.contains)) should have size 0 // linter:ignore:AvoidOptionMethod
        update.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 2 // linter:ignore:AvoidOptionMethod
      }

      "Assign dynamic service ports w/ both BRIDGE and USER containers" in {
        val servicePortsRange = 0.until(12)
        val bridgeModeContainer = Some(prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8080, hostPort = Some(0))
        )
        ))
        val userModeContainer = Some(prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8081),
          PortMapping(containerPort = 8082, hostPort = Some(0))
        )
        ))
        val bridgeModeApp = AppDefinition("/bridgemodeapp".toPath, container = bridgeModeContainer, networks = Seq(BridgeNetwork()), cmd = Some("sleep"))
        val userModeApp = AppDefinition("/usermodeapp".toPath, container = userModeContainer, networks = Seq(ContainerNetwork("whatever")), cmd = Some("sleep"))
        val fromGroup = createRootGroup(Map(bridgeModeApp.id -> bridgeModeApp))
        val toGroup = createRootGroup(Map(
          bridgeModeApp.id -> bridgeModeApp,
          userModeApp.id -> userModeApp
        ))

        val groupsV1 = AssignDynamicServiceLogic.assignDynamicServicePorts(servicePortsRange, createRootGroup(), fromGroup)
        groupsV1.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
        groupsV1.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 1

        val groupsV2 = AssignDynamicServiceLogic.assignDynamicServicePorts(servicePortsRange, groupsV1, toGroup)
        groupsV2.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
        val assignedServicePorts = groupsV2.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains))
        assignedServicePorts should have size 3
      }

      "Assign a service port for an app using Docker USER networking with a default port mapping" in {
        val servicePortsRange = 10.to(11)
        val c1 = Some(prototype.copyWith(portMappings = Seq(
          PortMapping()
        )
        ))
        val app1 = AppDefinition("/app1".toPath, portDefinitions = Seq(), container = c1, networks = Seq(ContainerNetwork("whatever")), cmd = Some("sleep"))
        val rootGroup = createRootGroup(Map(app1.id -> app1))
        val update = AssignDynamicServiceLogic.assignDynamicServicePorts(servicePortsRange, createRootGroup(), rootGroup)
        update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
        update.transitiveApps.flatMap(_.hostPorts.flatten) should have size 0 // linter:ignore:AvoidOptionMethod
        update.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 1 // linter:ignore:AvoidOptionSize
      }

      // Regression test for #1365
      "Export non-dynamic service ports specified in the container to the ports field" in {
        val servicePortsRange = 10.to(20)
        val container = prototype.copyWith(portMappings = Seq(
          PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 80, protocol = "tcp"),
          PortMapping(containerPort = 9000, hostPort = Some(10555), servicePort = 81, protocol = "udp")
        )
        )
        val app1 = AppDefinition("/app1".toPath, container = Some(container), networks = Seq(ContainerNetwork("foo")), cmd = Some("sleep"))
        val rootGroup = createRootGroup(Map(app1.id -> app1))
        val update = AssignDynamicServiceLogic.assignDynamicServicePorts(servicePortsRange, createRootGroup(), rootGroup)
        update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
        update.transitiveApps.flatMap(_.servicePorts) should contain theSameElementsAs(Vector(80, 81))
      }

      "Retain the original container definition if port mappings are missing" in {
        val servicePortsRange = 10.to(15)
        val container: Container = prototype.copyWith(portMappings = Nil)

        val app1 = AppDefinition(
          id = "/app1".toPath,
          cmd = Some("sleep"),
          container = Some(container)
        )
        val rootGroup = createRootGroup(Map(app1.id -> app1))

        val result = AssignDynamicServiceLogic.assignDynamicServicePorts(servicePortsRange, createRootGroup(), rootGroup)
        result.apps.size should be(1)
        val app = result.apps.head._2
        app.container should be(Some(container))
      }
    }
  }

  behave like withContainerNetworking("docker-docker", Container.Docker(image = "foobar"))
  behave like withContainerNetworking("mesos-docker", Container.MesosDocker(image = "foobar"))
  behave like withContainerNetworking("mesos-appc", Container.MesosAppC(image = "foobar"))
  behave like withContainerNetworking("mesos", Container.Mesos())

}
