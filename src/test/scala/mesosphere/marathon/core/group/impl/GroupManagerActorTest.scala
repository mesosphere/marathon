package mesosphere.marathon
package core.group.impl

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

import akka.Done
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.testkit.TestActorRef
import akka.util.Timeout
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.event.GroupChangeSuccess
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.{ AppRepository, GroupRepository }
import mesosphere.marathon.test.{ GroupCreation, MarathonSpec, Mockito }
import mesosphere.marathon.util.WorkQueue
import org.mockito.Mockito.when
import org.rogach.scallop.ScallopConf
import org.scalatest.Matchers

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

class GroupManagerActorTest extends Mockito with Matchers with MarathonSpec with GroupCreation {

  val actorId = new AtomicInteger(0)

  test("Assign dynamic app ports") {
    val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0))
    val app2 = AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(1, 2, 3))
    val app3 = AppDefinition("/app3".toPath, portDefinitions = PortDefinitions(0, 2, 0))
    val rootGroup = createRootGroup(Map(
      app1.id -> app1,
      app2.id -> app2,
      app3.id -> app3
    ))
    val servicePortsRange = 10 to 20
    val update = manager(servicePortsRange).assignDynamicServicePorts(createRootGroup(), rootGroup)
    update.transitiveApps.filter(_.hasDynamicServicePorts) should be(empty)
    update.transitiveApps.flatMap(_.portNumbers.filter(servicePortsRange.contains)) should have size 5
  }

  test("apps with port definitions should map dynamic ports to a non-0 value") {
    val app = AppDefinition("/app".toRootPath, portDefinitions = Seq(PortDefinition(0), PortDefinition(1)))
    val rootGroup = createRootGroup(Map(app.id -> app))
    val update = manager(10 to 20).assignDynamicServicePorts(createRootGroup(), rootGroup)
    update.apps(app.id).portDefinitions.size should equal(2)
    update.apps(app.id).portDefinitions should contain(PortDefinition(1))
    update.apps(app.id).portDefinitions should not contain PortDefinition(0)
  }

  test("Assign dynamic service ports specified in the container") {
    import Container.{ Docker, PortMapping }
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    val container = Docker(
      image = "busybox",
      network = Some(Network.BRIDGE),
      portMappings = Seq(
        PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 0, protocol = "tcp"),
        PortMapping(containerPort = 9000, hostPort = Some(10555), servicePort = 10555, protocol = "udp"),
        PortMapping(containerPort = 9001, hostPort = Some(31337), servicePort = 0, protocol = "udp"),
        PortMapping(containerPort = 9002, hostPort = Some(0), servicePort = 0, protocol = "tcp")
      )
    )
    val app = AppDefinition("/app1".toPath, portDefinitions = Seq(), container = Some(container))
    val rootGroup = createRootGroup(Map(app.id -> app))
    val servicePortsRange = 10 to 14
    val updatedGroup = manager(servicePortsRange).assignDynamicServicePorts(createRootGroup(), rootGroup)
    val updatedApp = updatedGroup.transitiveApps.head
    updatedApp.hasDynamicServicePorts should be (false)
    updatedApp.hostPorts should have size 4
    updatedApp.servicePorts should have size 4
    updatedApp.servicePorts.filter(servicePortsRange.contains) should have size 3
  }

  test("Assign dynamic service ports specified in multiple containers") {
    import Container.{ Docker, PortMapping }
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    val c1 = Some(Docker(
      image = "busybox",
      network = Some(Network.USER),
      portMappings = Seq(
        PortMapping(containerPort = 8080)
      )
    ))
    val c2 = Some(Docker(
      image = "busybox",
      network = Some(Network.USER),
      portMappings = Seq(
        PortMapping(containerPort = 8081)
      )
    ))
    val app1 = AppDefinition("/app1".toPath, portDefinitions = Seq(), container = c1)
    val app2 = AppDefinition("/app2".toPath, portDefinitions = Seq(), container = c2)
    val rootGroup = createRootGroup(Map(
      app1.id -> app1,
      app2.id -> app2
    ))
    val servicePortsRange = 10 to 12
    val update = manager(servicePortsRange).assignDynamicServicePorts(createRootGroup(), rootGroup)
    update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
    update.transitiveApps.flatMap(_.hostPorts.flatten.filter(servicePortsRange.contains)) should have size 0 // linter:ignore:AvoidOptionMethod
    update.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 2 // linter:ignore:AvoidOptionMethod
  }

  test("Assign dynamic service ports w/ both BRIDGE and USER containers") {
    import Container.{ Docker, PortMapping }
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    val bridgeModeContainer = Some(Docker(
      image = "busybox",
      network = Some(Network.BRIDGE),
      portMappings = Seq(
        PortMapping(containerPort = 8080, hostPort = Some(0))
      )
    ))
    val userModeContainer = Some(Docker(
      image = "busybox",
      network = Some(Network.USER),
      portMappings = Seq(
        PortMapping(containerPort = 8081),
        PortMapping(containerPort = 8082, hostPort = Some(0))
      )
    ))
    val bridgeModeApp = AppDefinition("/bridgemodeapp".toPath, container = bridgeModeContainer)
    val userModeApp = AppDefinition("/usermodeapp".toPath, container = userModeContainer)
    val fromGroup = createRootGroup(Map(bridgeModeApp.id -> bridgeModeApp))
    val toGroup = createRootGroup(Map(
      bridgeModeApp.id -> bridgeModeApp,
      userModeApp.id -> userModeApp
    ))

    val servicePortsRange = 0 until 12
    val groupManager = manager(servicePortsRange)
    val groupsV1 = groupManager.assignDynamicServicePorts(createRootGroup(), fromGroup)
    groupsV1.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
    groupsV1.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 1

    val groupsV2 = groupManager.assignDynamicServicePorts(groupsV1, toGroup)
    groupsV2.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
    val assignedServicePorts = groupsV2.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains))
    assignedServicePorts should have size 3
  }

  test("Assign a service port for an app using Docker USER networking with a default port mapping") {
    import Container.{ Docker, PortMapping }
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    val c1 = Some(Docker(
      image = "busybox",
      network = Some(Network.USER),
      portMappings = Seq(
        PortMapping()
      )
    ))
    val app1 = AppDefinition("/app1".toPath, portDefinitions = Seq(), container = c1)
    val rootGroup = createRootGroup(Map(app1.id -> app1))
    val servicePortsRange = 10 to 11
    val update = manager(servicePortsRange).assignDynamicServicePorts(createRootGroup(), rootGroup)
    update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
    update.transitiveApps.flatMap(_.hostPorts.flatten) should have size 0 // linter:ignore:AvoidOptionMethod
    update.transitiveApps.flatMap(_.servicePorts.filter(servicePortsRange.contains)) should have size 1 // linter:ignore:AvoidOptionSize
  }

  //regression for #2743
  test("Reassign dynamic service ports specified in the container") {
    val app = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 11))
    val updatedApp = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 0, 11))
    val from = createRootGroup(Map(app.id -> app))
    val to = createRootGroup(Map(updatedApp.id -> updatedApp))
    val update = manager(10 to 20).assignDynamicServicePorts(from, to)
    update.app("/app1".toPath).get.portNumbers should be(Seq(10, 12, 11))
  }

  // Regression test for #1365
  test("Export non-dynamic service ports specified in the container to the ports field") {
    import Container.{ Docker, PortMapping }
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
    val container = Docker(
      image = "busybox",
      network = Some(Network.BRIDGE),
      portMappings = Seq(
        PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 80, protocol = "tcp"),
        PortMapping(containerPort = 9000, hostPort = Some(10555), servicePort = 81, protocol = "udp")
      )
    )
    val app1 = AppDefinition("/app1".toPath, container = Some(container))
    val rootGroup = createRootGroup(Map(app1.id -> app1))
    val update = manager(90 to 900).assignDynamicServicePorts(createRootGroup(), rootGroup)
    update.transitiveApps.filter(_.hasDynamicServicePorts) should be (empty)
    update.transitiveApps.flatMap(_.portNumbers) should equal (Set(80, 81))
  }

  test("Already taken ports will not be used") {
    val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0))
    val app2 = AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(0, 2, 0))
    val rootGroup = createRootGroup(Map(
      app1.id -> app1,
      app2.id -> app2
    ))
    val servicePortsRange = 10 to 20
    val update = manager(servicePortsRange).assignDynamicServicePorts(createRootGroup(), rootGroup)
    update.transitiveApps.filter(_.hasDynamicServicePorts) should be(empty)
    update.transitiveApps.flatMap(_.portNumbers.filter(servicePortsRange.contains)) should have size 5
  }

  // Regression test for #2868
  test("Don't assign duplicated service ports") {
    val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 10))
    val rootGroup = createRootGroup(Map(app1.id -> app1))
    val update = manager(10 to 20).assignDynamicServicePorts(createRootGroup(), rootGroup)

    val assignedPorts: Set[Int] = update.transitiveApps.flatMap(_.portNumbers)
    assignedPorts should have size 2
  }

  test("Assign unique service ports also when adding a dynamic service port to an app") {
    val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(10, 11))
    val originalGroup = createRootGroup(Map(app1.id -> app1))

    val updatedApp1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0))
    val updatedGroup = createRootGroup(Map(updatedApp1.id -> updatedApp1))
    val result = manager(10 to 20).assignDynamicServicePorts(originalGroup, updatedGroup)

    val assignedPorts: Set[Int] = result.transitiveApps.flatMap(_.portNumbers)
    assignedPorts should have size 3
  }

  test("If there are not enough ports, a PortExhausted exception is thrown") {
    val app1 = AppDefinition("/app1".toPath, portDefinitions = PortDefinitions(0, 0, 0))
    val app2 = AppDefinition("/app2".toPath, portDefinitions = PortDefinitions(0, 0, 0))
    val rootGroup = createRootGroup(Map(
      app1.id -> app1,
      app2.id -> app2
    ))
    val ex = intercept[PortRangeExhaustedException] {
      manager(10 to 14).assignDynamicServicePorts(createRootGroup(), rootGroup)
    }
    ex.minPort should be(10)
    ex.maxPort should be(15)
  }

  test("Retain the original container definition if port mappings are missing") {
    val container = Container.Docker(image = "busybox")

    val app1 = AppDefinition(
      id = "/app1".toPath,
      container = Some(container)
    )
    val rootGroup = createRootGroup(Map(app1.id -> app1))

    val result = manager(10 to 15).assignDynamicServicePorts(createRootGroup(), rootGroup)
    result.apps.size should be(1)
    val app = result.apps.head._2
    app.container should be (Some(container))
  }

  test("Don't store invalid groups") {
    val f = new Fixture

    val app1 = AppDefinition("/app1".toPath)
    val rootGroup = createRootGroup(Map(app1.id -> app1), groups = Set(createGroup("/group1".toPath)))

    when(f.groupRepo.root()).thenReturn(Future.successful(createRootGroup()))

    intercept[ValidationFailedException] {
      Await.result(f.manager ? putGroup(rootGroup), 3.seconds)
    }.printStackTrace()

    verify(f.groupRepo, times(0)).storeRoot(any, any, any, any, any)
  }

  test("publishes GroupChangeSuccess with the appropriate GID on successful deployment") {
    val f = new Fixture

    val app: AppDefinition = AppDefinition("/group/app1".toPath, cmd = Some("sleep 3"), portDefinitions = Seq.empty)
    val group = createGroup("/group".toPath, apps = Map(app.id -> app), version = Timestamp(1))
    val rootGroup = createRootGroup(
      version = Timestamp(1),
      groups = Set(group))

    when(f.groupRepo.root()).thenReturn(Future.successful(createRootGroup()))
    when(f.scheduler.deploy(any, any)).thenReturn(Future.successful(()))
    val appWithVersionInfo = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))

    val groupWithVersionInfo = createRootGroup(
      version = Timestamp(1),
      groups = Set(
        createGroup(
          "/group".toPath, apps = Map(appWithVersionInfo.id -> appWithVersionInfo), version = Timestamp(1))))
    when(f.groupRepo.storeRootVersion(any, any, any)).thenReturn(Future.successful(Done))
    when(f.groupRepo.storeRoot(any, any, any, any, any)).thenReturn(Future.successful(Done))
    val groupChangeSuccess = Promise[GroupChangeSuccess]
    f.eventBus.publish(any).answers {
      case Array(change: GroupChangeSuccess) =>
        groupChangeSuccess.success(change)
      case _ =>
        ???
    }

    (f.manager ? putGroup(group, version = Timestamp(1))).futureValue
    verify(f.groupRepo).storeRoot(groupWithVersionInfo, Seq(appWithVersionInfo), Nil, Nil, Nil)
    verify(f.groupRepo).storeRootVersion(groupWithVersionInfo, Seq(appWithVersionInfo), Nil)

    groupChangeSuccess.future.
      futureValue.
      groupId shouldBe "/group".toPath
  }

  test("Store new apps with correct version infos in groupRepo and appRepo") {
    val f = new Fixture

    val app: AppDefinition = AppDefinition("/app1".toPath, cmd = Some("sleep 3"), portDefinitions = Seq.empty)
    val rootGroup = createRootGroup(Map(app.id -> app), version = Timestamp(1))
    when(f.groupRepo.root()).thenReturn(Future.successful(createRootGroup()))
    when(f.scheduler.deploy(any, any)).thenReturn(Future.successful(()))
    val appWithVersionInfo = app.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(1)))

    val groupWithVersionInfo = createRootGroup(Map(
      appWithVersionInfo.id -> appWithVersionInfo), version = Timestamp(1))
    when(f.groupRepo.storeRootVersion(any, any, any)).thenReturn(Future.successful(Done))
    when(f.groupRepo.storeRoot(any, any, any, any, any)).thenReturn(Future.successful(Done))

    Await.result(f.manager ? putGroup(rootGroup, version = Timestamp(1)), 3.seconds)

    verify(f.groupRepo).storeRoot(groupWithVersionInfo, Seq(appWithVersionInfo), Nil, Nil, Nil)
    verify(f.groupRepo).storeRootVersion(groupWithVersionInfo, Seq(appWithVersionInfo), Nil)
  }

  test("Expunge removed apps from appRepo") {
    val f = new Fixture

    val app: AppDefinition = AppDefinition("/app1".toPath, cmd = Some("sleep 3"), portDefinitions = Seq.empty)
    val rootGroup = createRootGroup(Map(app.id -> app), version = Timestamp(1))
    val groupEmpty = createRootGroup(version = Timestamp(1))
    when(f.groupRepo.root()).thenReturn(Future.successful(rootGroup))
    when(f.scheduler.deploy(any, any)).thenReturn(Future.successful(()))
    when(f.appRepo.delete(any)).thenReturn(Future.successful(Done))
    when(f.groupRepo.storeRootVersion(any, any, any)).thenReturn(Future.successful(Done))
    when(f.groupRepo.storeRoot(any, any, any, any, any)).thenReturn(Future.successful(Done))

    Await.result(f.manager ? putGroup(groupEmpty, version = Timestamp(1)), 3.seconds)

    verify(f.groupRepo).storeRoot(groupEmpty, Nil, Seq(app.id), Nil, Nil)
    verify(f.groupRepo).storeRootVersion(groupEmpty, Nil, Nil)
    verify(f.appRepo, atMost(1)).delete(app.id)
    verify(f.appRepo, atMost(1)).deleteCurrent(app.id)
  }

  private[this] implicit val timeout: Timeout = 3.seconds

  class Fixture {
    implicit val system = ActorSystem()
    implicit val mat = ActorMaterializer()
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

    val schedulerProvider = new Provider[DeploymentService] {
      override def get() = scheduler
    }

    val props = GroupManagerActor.props(
      WorkQueue("GroupManager", 1, 10),
      schedulerProvider,
      groupRepo,
      provider,
      config,
      eventBus,
      metrics)

    lazy val manager = system.actorOf(props)
  }

  private def manager(servicePortsRange: Range): GroupManagerActor = {
    val f = new Fixture {
      override lazy val config = new ScallopConf(Seq(
        "--master", "foo",
        "--local_port_min", servicePortsRange.start.toString,
        // local_port_max is not included in the range used by the manager
        "--local_port_max", (servicePortsRange.end + 1).toString)) with MarathonConf {
        verify()
      }

      override lazy val manager = TestActorRef[GroupManagerActor](props)
    }

    f.manager.underlyingActor
  }

  private def putGroup(group: Group, version: Timestamp = Timestamp.now()) =
    GroupManagerActor.GetUpgrade(group.id, _.putGroup(group, version), version)
}
