package mesosphere.marathon.state

import akka.event.EventStream
import org.rogach.scallop.ScallopConf
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ FunSuite, Matchers }

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{ PortRangeExhaustedException, MarathonConf, MarathonSchedulerService }

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

  test("Already taken ports will not be used") {
    val current = Group(PathId.empty, Set(
      AppDefinition("/app1".toPath, ports = Seq(10, 11, 12)),
      AppDefinition("/app2".toPath, ports = Seq(13, 14, 15))
    ))
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

  def manager(from: Int, to: Int) = {
    val config = new ScallopConf(Seq("--master", "foo", "--local_port_min", from.toString, "--local_port_max", to.toString)) with MarathonConf
    config.afterInit()
    val scheduler = mock[MarathonSchedulerService]
    val taskTracker = mock[TaskTracker]
    val groupRepo = mock[GroupRepository]
    val appRepo = mock[AppRepository]
    val eventBus = mock[EventStream]
    new GroupManager(scheduler, taskTracker, groupRepo, appRepo, config, eventBus)
  }
}
