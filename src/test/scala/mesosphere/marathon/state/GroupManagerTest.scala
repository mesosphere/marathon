package mesosphere.marathon.state

import java.util.concurrent.atomic.AtomicInteger

import akka.event.EventStream
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.util.{ CapConcurrentExecutionsMetrics, CapConcurrentExecutions }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, MarathonSpec, PortRangeExhaustedException }
import mesosphere.marathon._
import org.mockito.Matchers.any
import org.mockito.Mockito.{ times, verify, when }
import org.rogach.scallop.ScallopConf
import org.scalatest.Matchers
import org.scalatest.mock.MockitoSugar

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class GroupManagerTest extends MarathonActorSupport with MockitoSugar with Matchers with MarathonSpec {

  val actorId = new AtomicInteger(0)

  class Fixture {
    lazy val scheduler = mock[MarathonSchedulerService]
    lazy val appRepo = mock[AppRepository]
    lazy val groupRepo = mock[GroupRepository]
    lazy val eventBus = mock[EventStream]
    lazy val provider = mock[StorageProvider]
    lazy val config = {
      new ScallopConf(Seq("--master", "foo")) with MarathonConf {
        verify()
      }
    }

    lazy val metricRegistry = new MetricRegistry()
    lazy val metrics = new Metrics(metricRegistry)
    lazy val capMetrics = new CapConcurrentExecutionsMetrics(metrics, classOf[GroupManager])

    def serializeExecutions() = CapConcurrentExecutions(
      capMetrics,
      system,
      s"serializeGroupUpdates${actorId.incrementAndGet()}",
      maxParallel = 1,
      maxQueued = 10
    )

    lazy val manager = new GroupManager(
      serializeUpdates = serializeExecutions(), scheduler = scheduler,
      groupRepo = groupRepo, appRepo = appRepo,
      storage = provider, config = config, eventBus = eventBus)

  }

  test("Assign dynamic app ports") {
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0)),
      AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(1, 2, 3)),
      AppDefinition("/app3".toPath, portDefinitions = PortDefinitions(0, 2, 0))
    ))
    val servicePortsRange = 10 to 20
    val update = manager(servicePortsRange).assignDynamicServicePorts(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicServicePorts) should be(empty)
    update.transitiveApps.flatMap(_.portNumbers.filter(servicePortsRange.contains)) should have size 5
  }

  test("Assign dynamic service ports specified in the container") {
    import Container.Docker
    import Docker.PortMapping
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    val container = Container(
      docker = Some(Docker(
        image = "busybox",
        network = Some(Network.BRIDGE),
        portMappings = Some(Seq(
          PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 0, protocol = "tcp"),
          PortMapping(containerPort = 9000, hostPort = Some(10555), servicePort = 10555, protocol = "udp"),
          PortMapping(containerPort = 9001, hostPort = Some(31337), servicePort = 0, protocol = "udp"),
          PortMapping(containerPort = 9002, hostPort = Some(0), servicePort = 0, protocol = "tcp")
        ))
      ))
    )
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, portDefinitions = Seq(), container = Some(container))
    ))
    val servicePortsRange = 10 to 14
    val updatedGroup = manager(servicePortsRange).assignDynamicServicePorts(Group.empty, group)
    val updatedApp = updatedGroup.transitiveApps.head
    updatedApp.hasDynamicServicePorts should be (false)
    updatedApp.hostPorts should have size 4
    updatedApp.servicePorts should have size 4
    updatedApp.servicePorts.filter(servicePortsRange.contains) should have size 3
  }

  test("Assign dynamic service ports specified in multiple containers") {
    import Container.Docker
    import Docker.PortMapping
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    val c1 = Some(Container(
      docker = Some(Docker(
        image = "busybox",
        network = Some(Network.USER),
        portMappings = Some(Seq(
          PortMapping(containerPort = 8080)
        ))
      ))
    ))
    val c2 = Some(Container(
      docker = Some(Docker(
        image = "busybox",
        network = Some(Network.USER),
        portMappings = Some(Seq(
          PortMapping(containerPort = 8081)
        ))
      ))
    ))
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, portDefinitions = Seq(), container = c1),
      AppDefinition("/app2".toPath, portDefinitions = Seq(), container = c2)
    ))
    val servicePortsRange = 10 to 12
    val update = manager(servicePortsRange).assignDynamicServicePorts(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
    update.transitiveApps.flatMap(_.hostPorts.flatten.filter(servicePortsRange.contains)).toSet should have size 0
    update.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)).toSet should have size 2
  }

  test("Assign dynamic service ports w/ both BRIDGE and USER containers") {
    import Container.Docker
    import Docker.PortMapping
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    val bridgeModeContainer = Some(Container(
      docker = Some(Docker(
        image = "busybox",
        network = Some(Network.BRIDGE),
        portMappings = Some(Seq(
          PortMapping(containerPort = 8080, hostPort = Some(0))
        ))
      ))
    ))
    val userModeContainer = Some(Container(
      docker = Some(Docker(
        image = "busybox",
        network = Some(Network.USER),
        portMappings = Some(Seq(
          PortMapping(containerPort = 8081),
          PortMapping(containerPort = 8082, hostPort = Some(0))
        ))
      ))
    ))
    val fromGroup = Group(PathId.empty, Set(
      AppDefinition("/bridgemodeapp".toPath, container = bridgeModeContainer)
    ))
    val toGroup = Group(PathId.empty, Set(
      AppDefinition("/bridgmodeeapp".toPath, container = bridgeModeContainer),
      AppDefinition("/usermodeapp".toPath, container = userModeContainer)
    ))

    val servicePortsRange = 0 until 12
    val groupManager = manager(servicePortsRange)
    val groupsV1 = groupManager.assignDynamicServicePorts(Group.empty, fromGroup)
    groupsV1.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
    groupsV1.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 1

    val groupsV2 = groupManager.assignDynamicServicePorts(groupsV1, toGroup)
    groupsV2.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
    val assignedServicePorts = groupsV2.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains))
    assignedServicePorts should have size 3
  }

  test("Assign a service port for an app using Docker USER networking with a default port mapping") {
    import Container.Docker
    import Docker.PortMapping
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    val c1 = Some(Container(
      docker = Some(Docker(
        image = "busybox",
        network = Some(Network.USER),
        portMappings = Some(Seq(
          PortMapping()
        ))
      ))
    ))
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, portDefinitions = Seq(), container = c1)
    ))
    val servicePortsRange = 10 to 11
    val update = manager(servicePortsRange).assignDynamicServicePorts(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
    update.transitiveApps.flatMap(_.hostPorts.flatten) should have size 0
    update.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 1
  }

  //regression for #2743
  test("Reassign dynamic service ports specified in the container") {
    val from = Group(PathId.empty, Set(AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 11))))
    val to = Group(PathId.empty, Set(AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 0, 11))))
    val update = manager(10 to 20).assignDynamicServicePorts(from, to)
    update.app("/app1".toPath).get.portNumbers should be(Seq(10, 12, 11))
  }

  // Regression test for #1365
  test("Export non-dynamic service ports specified in the container to the ports field") {
    import Container.Docker
    import Docker.PortMapping
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    val container = Container(
      docker = Some(Docker(
        image = "busybox",
        network = Some(Network.BRIDGE),
        portMappings = Some(Seq(
          PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 80, protocol = "tcp"),
          PortMapping (containerPort = 9000, hostPort = Some(10555), servicePort = 81, protocol = "udp")
        ))
      ))
    )
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, container = Some(container))
    ))
    val update = manager(90 to 900).assignDynamicServicePorts(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
    update.transitiveApps.flatMap(_.portNumbers) should equal (Set(80, 81))
  }

  test("Already taken ports will not be used") {
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0)),
      AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(0, 2, 0))
    ))
    val servicePortsRange = 10 to 20
    val update = manager(servicePortsRange).assignDynamicServicePorts(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicServicePorts) should be(empty)
    update.transitiveApps.flatMap(_.portNumbers.filter(servicePortsRange.contains)) should have size 5
  }

  // Regression test for #2868
  test("Don't assign duplicated service ports") {
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 10))
    ))
    val update = manager(10 to 20).assignDynamicServicePorts(Group.empty, group)

    val assignedPorts: Set[Int] = update.transitiveApps.flatMap(_.portNumbers)
    assignedPorts should have size 2
  }

  test("Assign unique service ports also when adding a dynamic service port to an app") {
    val originalGroup = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 11))
    ))

    val updatedGroup = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0))
    ))
    val result = manager(10 to 20).assignDynamicServicePorts(originalGroup, updatedGroup)

    val assignedPorts: Set[Int] = result.transitiveApps.flatMap(_.portNumbers)
    assignedPorts should have size 3
  }

  test("If there are not enough ports, a PortExhausted exception is thrown") {
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0)),
      AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(0, 0, 0))
    ))
    val ex = intercept[PortRangeExhaustedException] {
      manager(10 to 14).assignDynamicServicePorts(Group.empty, group)
    }
    ex.minPort should be(10)
    ex.maxPort should be(15)
  }

  test("Retain the original container definition if port mappings are missing") {
    import Container.Docker

    val container = Container(
      docker = Some(Docker(
        image = "busybox"
      ))
    )

    val group = Group(PathId.empty, Set(
      AppDefinition(
        id = "/app1".toPath,
        container = Some(container)
      )
    ))

    val result = manager(10 to 15).assignDynamicServicePorts(Group.empty, group)
    result.apps.size should be(1)
    val app = result.apps.head
    app.container should be (Some(container))
  }

  test("Don't store invalid groups") {
    val f = new Fixture

    val group = Group(PathId.empty, Set(AppDefinition("/app1".toPath)), Set(Group("/group1".toPath)))

    when(f.groupRepo.zkRootName).thenReturn(GroupRepository.zkRootName)
    when(f.groupRepo.group(GroupRepository.zkRootName)).thenReturn(Future.successful(None))

    intercept[ValidationFailedException] {
      Await.result(f.manager.update(group.id, _ => group), 3.seconds)
    }.printStackTrace()

    verify(f.groupRepo, times(0)).store(any(), any())
  }

  test("Store new apps with correct version infos in groupRepo and appRepo") {
    val f = new Fixture

    val app: AppDefinition = AppDefinition("/app1".toPath, cmd = Some("sleep 3"), portDefinitions = Seq.empty)
    val group = Group(PathId.empty, Set(app)).copy(version = Timestamp(1))
    when(f.groupRepo.zkRootName).thenReturn(GroupRepository.zkRootName)
    when(f.groupRepo.group(GroupRepository.zkRootName)).thenReturn(Future.successful(None))
    when(f.scheduler.deploy(any(), any())).thenReturn(Future.successful(()))
    val appWithVersionInfo = app.copy(versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(1)))
    val groupWithVersionInfo = Group(PathId.empty, Set(appWithVersionInfo)).copy(version = Timestamp(1))
    when(f.appRepo.store(any())).thenReturn(Future.successful(appWithVersionInfo))
    when(f.groupRepo.store(any(), any())).thenReturn(Future.successful(groupWithVersionInfo))

    Await.result(f.manager.update(group.id, _ => group, version = Timestamp(1)), 3.seconds)

    verify(f.groupRepo).store(GroupRepository.zkRootName, groupWithVersionInfo)
    verify(f.appRepo).store(appWithVersionInfo)
  }

  test("Expunge removed apps from appRepo") {
    val f = new Fixture

    val app: AppDefinition = AppDefinition("/app1".toPath, cmd = Some("sleep 3"), portDefinitions = Seq.empty)
    val group = Group(PathId.empty, Set(app)).copy(version = Timestamp(1))
    val groupEmpty = group.copy(apps = Set(), version = Timestamp(2))
    when(f.groupRepo.zkRootName).thenReturn(GroupRepository.zkRootName)
    when(f.groupRepo.group(GroupRepository.zkRootName)).thenReturn(Future.successful(Some(group)))
    when(f.scheduler.deploy(any(), any())).thenReturn(Future.successful(()))
    when(f.appRepo.expunge(any())).thenReturn(Future.successful(Seq(true)))
    when(f.groupRepo.store(any(), any())).thenReturn(Future.successful(groupEmpty))

    Await.result(f.manager.update(group.id, _ => groupEmpty, version = Timestamp(1)), 3.seconds)

    verify(f.groupRepo).store(GroupRepository.zkRootName, groupEmpty)
    verify(f.appRepo).expunge(app.id)
  }

  def manager(servicePortsRange: Range) = {
    val f = new Fixture {
      override lazy val config = new ScallopConf(Seq(
        "--master", "foo",
        "--local_port_min", servicePortsRange.start.toString,
        // local_port_max is not included in the range used by the manager
        "--local_port_max", (servicePortsRange.end + 1).toString)) with MarathonConf {
        verify()
      }
    }

    f.manager
  }
}
