package mesosphere.marathon.state

import javax.validation.ConstraintViolationException

import akka.event.EventStream
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, PortRangeExhaustedException }
import org.mockito.Matchers.any
import org.mockito.Mockito.{ times, verify, when }
import org.rogach.scallop.ScallopConf
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ FunSuite, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class GroupManagerTest extends FunSuite with MockitoSugar with Matchers {

  test("Assign dynamic app ports") {
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, ports = Seq(0, 0, 0)),
      AppDefinition("/app2".toPath, ports = Seq(1, 2, 3)),
      AppDefinition("/app2".toPath, ports = Seq(0, 2, 0))
    ))
    val update = manager(10, 20).assignDynamicAppPort(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicPort) should be('empty)
    update.transitiveApps.flatMap(_.ports.filter(x => x >= 10 && x <= 20)) should have size 5
  }

  test("Assign dynamic app ports specified in the container") {
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
    val update = manager(10, 20).assignDynamicAppPort(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicPort) should be ('empty)
    update.transitiveApps.flatMap(_.ports.filter(x => x >= 10 && x <= 20)) should have size 2
  }

  test("Already taken ports will not be used") {
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, ports = Seq(0, 0, 0)),
      AppDefinition("/app2".toPath, ports = Seq(0, 2, 0))
    ))
    val update = manager(10, 20).assignDynamicAppPort(Group.empty, group)
    update.transitiveApps.filter(_.hasDynamicPort) should be('empty)
    update.transitiveApps.flatMap(_.ports.filter(x => x >= 10 && x <= 20)) should have size 5
  }

  test("If there are not enough ports, a PortExhausted exception is thrown") {
    val group = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, ports = Seq(0, 0, 0)),
      AppDefinition("/app2".toPath, ports = Seq(0, 0, 0))
    ))
    val ex = intercept[PortRangeExhaustedException] {
      manager(10, 15).assignDynamicAppPort(Group.empty, group)
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

    val result = manager(10, 15).assignDynamicAppPort(Group.empty, group)
    result.apps.size should be(1)
    val app = result.apps.head
    app.container should be (Some(container))
  }

  test("Don't store invalid groups") {
    val scheduler = mock[MarathonSchedulerService]
    val taskTracker = mock[TaskTracker]
    val groupRepo = mock[GroupRepository]
    val eventBus = mock[EventStream]
    val provider = mock[StorageProvider]
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()
    val manager = new GroupManager(scheduler, taskTracker, groupRepo, provider, config, eventBus)

    val group = Group(PathId.empty, Set(AppDefinition("/app1".toPath)), Set(Group("/group1".toPath)))

    when(groupRepo.group("root", withLatestApps = false)).thenReturn(Future.successful(None))

    intercept[ConstraintViolationException] {
      Await.result(manager.update(group.id, _ => group), 3.seconds)
    }.printStackTrace()

    verify(groupRepo, times(0)).store(any(), any())
  }

  def manager(from: Int, to: Int) = {
    val config = new ScallopConf(Seq("--master", "foo", "--local_port_min", from.toString, "--local_port_max", to.toString)) with MarathonConf
    config.afterInit()
    val scheduler = mock[MarathonSchedulerService]
    val taskTracker = mock[TaskTracker]
    val groupRepo = mock[GroupRepository]
    val eventBus = mock[EventStream]
    val provider = mock[StorageProvider]
    new GroupManager(scheduler, taskTracker, groupRepo, provider, config, eventBus)
  }
}
