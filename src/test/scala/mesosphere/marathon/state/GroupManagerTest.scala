package mesosphere.marathon.state

import java.util.concurrent.atomic.AtomicInteger
import javax.validation.ConstraintViolationException

import akka.actor.ActorSystem
import akka.event.EventStream
import akka.testkit.TestKit
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskTrackerImpl
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, MarathonSpec, PortRangeExhaustedException }
import mesosphere.util.SerializeExecution
import org.mockito.Matchers.any
import org.mockito.Mockito.{ times, verify, when }
import org.rogach.scallop.ScallopConf
import org.scalatest.Matchers
import org.scalatest.mock.MockitoSugar

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class GroupManagerTest extends TestKit(ActorSystem("System")) with MockitoSugar with Matchers with MarathonSpec {

  val actorId = new AtomicInteger(0)
  def serializeExecutions() = SerializeExecution(system, s"serializeGroupUpdates${actorId.incrementAndGet()}")

  class Fixture {
    lazy val scheduler = mock[MarathonSchedulerService]
    lazy val appRepo = mock[AppRepository]
    lazy val groupRepo = mock[GroupRepository]
    lazy val eventBus = mock[EventStream]
    lazy val provider = mock[StorageProvider]
    lazy val config = {
      val conf = new ScallopConf(Seq("--master", "foo")) with MarathonConf
      conf.afterInit()
      conf
    }

    lazy val manager = new GroupManager(
      serializeUpdates = serializeExecutions(), scheduler = scheduler,
      groupRepo = groupRepo, appRepo = appRepo,
      storage = provider, config = config, eventBus = eventBus)

  }

  test("Assign dynamic app ports") {
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, ports = Seq(0, 0, 0)),
      AppDefinition("/app2".toPath, ports = Seq(1, 2, 3)),
      AppDefinition("/app3".toPath, ports = Seq(0, 2, 0))
    ))
    val update = manager(10, 20).assignDynamicServicePorts(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicPort) should be('empty)
    update.transitiveApps.flatMap(_.ports.filter(x => x >= 10 && x <= 20)) should have size 5
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
          PortMapping(containerPort = 8080, hostPort = 0, servicePort = 0, protocol = "tcp"),
          PortMapping (containerPort = 9000, hostPort = 10555, servicePort = 10555, protocol = "udp"),
          PortMapping(containerPort = 9001, hostPort = 0, servicePort = 0, protocol = "tcp")
        ))
      ))
    )
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, ports = Seq(), container = Some(container))
    ))
    val update = manager(minServicePort = 10, maxServicePort = 20).assignDynamicServicePorts(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicPort) should be ('empty)
    update.transitiveApps.flatMap(_.ports.filter(x => x >= 10 && x <= 20)) should have size 2
  }

  //regression for #2743
  test("Reassign dynamic service ports specified in the container") {
    val from = Group(PathId.empty, Set(AppDefinition("/app1".toPath, ports = Seq(10, 11))))
    val to = Group(PathId.empty, Set(AppDefinition("/app1".toPath, ports = Seq(10, 0, 11))))
    val update = manager(minServicePort = 10, maxServicePort = 20).assignDynamicServicePorts(from, to)
    update.app("/app1".toPath).get.ports should be(Seq(10, 12, 11))
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
          PortMapping(containerPort = 8080, hostPort = 0, servicePort = 80, protocol = "tcp"),
          PortMapping (containerPort = 9000, hostPort = 10555, servicePort = 81, protocol = "udp")
        ))
      ))
    )
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, container = Some(container))
    ))
    val update = manager(minServicePort = 90, maxServicePort = 900).assignDynamicServicePorts(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicPort) should be ('empty)
    update.transitiveApps.flatMap(_.ports) should equal (Set(80, 81).map(Integer.valueOf))
  }

  test("Already taken ports will not be used") {
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, ports = Seq(0, 0, 0)),
      AppDefinition("/app2".toPath, ports = Seq(0, 2, 0))
    ))
    val update = manager(10, 20).assignDynamicServicePorts(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicPort) should be('empty)
    update.transitiveApps.flatMap(_.ports.filter(x => x >= 10 && x <= 20)) should have size 5
  }

  test("If there are not enough ports, a PortExhausted exception is thrown") {
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, ports = Seq(0, 0, 0)),
      AppDefinition("/app2".toPath, ports = Seq(0, 0, 0))
    ))
    val ex = intercept[PortRangeExhaustedException] {
      manager(10, 15).assignDynamicServicePorts(Group.empty, group)
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

    val result = manager(10, 15).assignDynamicServicePorts(Group.empty, group)
    result.apps.size should be(1)
    val app = result.apps.head
    app.container should be (Some(container))
  }

  test("Don't store invalid groups") {
    val f = new Fixture

    val group = Group(PathId.empty, Set(AppDefinition("/app1".toPath)), Set(Group("/group1".toPath)))

    when(f.groupRepo.zkRootName).thenReturn(GroupRepository.zkRootName)
    when(f.groupRepo.group(GroupRepository.zkRootName)).thenReturn(Future.successful(None))

    intercept[ConstraintViolationException] {
      Await.result(f.manager.update(group.id, _ => group), 3.seconds)
    }.printStackTrace()

    verify(f.groupRepo, times(0)).store(any(), any())
  }

  test("Store new apps with correct version infos in groupRepo and appRepo") {
    val f = new Fixture

    val app: AppDefinition = AppDefinition("/app1".toPath, cmd = Some("sleep 3"), ports = Seq.empty)
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

    val app: AppDefinition = AppDefinition("/app1".toPath, cmd = Some("sleep 3"), ports = Seq.empty)
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

  def manager(minServicePort: Int, maxServicePort: Int) = {
    val f = new Fixture {
      override lazy val config = new ScallopConf(Seq(
        "--master", "foo",
        "--local_port_min", minServicePort.toString, "--local_port_max", maxServicePort.toString)) with MarathonConf
    }

    f.config.afterInit()
    f.manager
  }
}
