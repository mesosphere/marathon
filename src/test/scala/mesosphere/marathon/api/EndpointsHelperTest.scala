package mesosphere.marathon
package api

import mesosphere.UnitTest
import mesosphere.marathon.api.EndpointsHelper.ListTasks
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.pod.{BridgeNetwork, ContainerNetwork, HostNetwork, Network}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.core.task.tracker.InstanceTracker.{InstancesBySpec, SpecInstances}
import mesosphere.marathon.state._
import org.apache.mesos.{Protos => Mesos}
import org.scalatest.Inside

import scala.collection.immutable.Seq

class EndpointsHelperTest extends UnitTest with Inside {
  private val allContainerNetworks = Set("*")
  private val noContainerNetworks = Set.empty[String]

  def parseOutput(output: String): List[(String, String, Seq[String])] = {
    output.trim
      .split("\n")
      .iterator
      .map {
        case line =>
          line.split("\t", -1).toList match {
            case app :: port :: endpoints =>
              (app, port, endpoints.filter(_.nonEmpty).sorted)
            case o =>
              throw new RuntimeException(s"parse error: ${o}")
          }
      }
      .toList
  }

  def instances(app: AppDefinition, taskCounts: Seq[Int]): InstancesBySpec = {
    val instances = SpecInstances(taskCounts.zipWithIndex.flatMap {
      case (numTasks, agentIndex) =>
        val agentId = agentIndex + 1
        val hostname = s"agent$agentId"
        val agent = AgentInfo(hostname, None, None, None, Nil)

        1.to(numTasks).map {
          taskIndex =>
            val instanceId = Instance.Id.forRunSpec(app.id)
            val ipAddresses: Seq[Mesos.NetworkInfo.IPAddress] =
              Seq(
                Mesos.NetworkInfo.IPAddress
                  .newBuilder()
                  .setIpAddress(s"1.1.1.${agentId * 10 + taskIndex}")
                  .setProtocol(Mesos.NetworkInfo.Protocol.IPv4)
                  .build
              )
            val hostPorts = app.hostPorts.zipWithIndex.flatMap {
              case (None, _) => None
              case (Some(_), i) if !app.requirePorts => Option(1000 + (taskIndex * 10) + i)
              case (portOption, _) => portOption
            }
            val taskId = Task.Id(instanceId)
            val task = Task(
              taskId,
              runSpecVersion = Timestamp.zero,
              status = Task.Status(
                stagedAt = Timestamp.zero,
                startedAt = None,
                mesosStatus = None,
                condition = Condition.Running,
                networkInfo = NetworkInfo(hostname, hostPorts = hostPorts, ipAddresses = ipAddresses)
              )
            )
            val state = Instance
              .InstanceState(condition = Condition.Running, since = Timestamp.zero, activeSince = None, healthy = None, Goal.Running)
            instanceId -> Instance(instanceId, Some(agent), state = state, tasksMap = Map(taskId -> task), app, None, "*")
        }
    }.toMap)
    InstancesBySpec(Map(app.id -> instances))
  }

  def fakeApp(
      appId: AbsolutePathId = AbsolutePathId("/foo"),
      container: => Option[Container] = None,
      networks: Seq[Network]
  ): AppDefinition =
    AppDefinition(appId, cmd = Option("sleep"), container = container, networks = networks, role = "*")

  def endpointsWithoutServicePorts(app: AppDefinition): Unit = {
    "handle single instance without service ports" in {
      val report = parseOutput(EndpointsHelper.appsToEndpointString(ListTasks(instances(app, Seq(1)), Seq(app)), allContainerNetworks))
      val expected = List(("foo", " ", Seq("agent1")))
      report should equal(expected)
    }
    "handle multiple instances, same agent, without service ports" in {
      val report = parseOutput(EndpointsHelper.appsToEndpointString(ListTasks(instances(app, Seq(2)), Seq(app)), allContainerNetworks))
      val expected = List(("foo", " ", Seq("agent1", "agent1")))
      report should equal(expected)
    }
    "handle multiple instances, different agents, without service ports" in {
      val report =
        parseOutput(EndpointsHelper.appsToEndpointString(ListTasks(instances(app, Seq(2, 1, 2)), Seq(app)), allContainerNetworks))
      val expected = List(("foo", " ", Seq("agent1", "agent1", "agent2", "agent3", "agent3")))
      report should equal(expected)
    }
  }

  def endpointsWithSingleDynamicServicePorts(app: AppDefinition): Unit = {
    "handle single instance with 1 service port" in {
      val report = EndpointsHelper.appsToEndpointString(ListTasks(instances(app, Seq(1)), Seq(app)), allContainerNetworks)
      val expected = "foo\t80\tagent1:1010\t\n"
      report should equal(expected)
    }
    "handle multiple instances, same agent, with 1 service port" in {
      val report = EndpointsHelper.appsToEndpointString(ListTasks(instances(app, Seq(2)), Seq(app)), allContainerNetworks)
      val expected = "foo\t80\tagent1:1010\tagent1:1020\t\n"
      report should equal(expected)
    }
    "handle multiple instances, different agents, with 1 service port" in {
      val report =
        parseOutput(EndpointsHelper.appsToEndpointString(ListTasks(instances(app, Seq(2, 1, 2)), Seq(app)), allContainerNetworks))
      val expected = List(("foo", "80", Seq("agent1:1010", "agent1:1020", "agent2:1010", "agent3:1010", "agent3:1020")))
      report should equal(expected)
    }
  }

  def endpointsWithSingleStaticServicePorts(app: AppDefinition, servicePort: Int, hostPort: Int): Unit = {
    s"handle single instance with 1 (static) service port $servicePort and host port $hostPort" in {
      val report = parseOutput(EndpointsHelper.appsToEndpointString(ListTasks(instances(app, Seq(1)), Seq(app)), allContainerNetworks))
      val expected = List(("foo", servicePort.toString, Seq(s"agent1:$hostPort")))
      report should equal(expected)
    }
    s"handle multiple instances, different agents, with 1 (static) service port $servicePort and host port $hostPort" in {
      val report = EndpointsHelper.appsToEndpointString(ListTasks(instances(app, Seq(1, 1, 1)), Seq(app)), allContainerNetworks)
      val expected = s"foo\t$servicePort\tagent1:$hostPort\tagent2:$hostPort\tagent3:$hostPort\t\n"
      report should equal(expected)
    }
  }

  "EndpointsHelper" when {
    "generating (host network) app service port reports" should {
      val hostNetworkedFakeApp = fakeApp(networks = Seq(HostNetwork))
      behave like endpointsWithoutServicePorts(hostNetworkedFakeApp)

      val singleServicePort = fakeApp(networks = Seq(HostNetwork)).copy(portDefinitions = PortDefinitions(80))

      behave like endpointsWithSingleDynamicServicePorts(singleServicePort)
      behave like endpointsWithSingleStaticServicePorts(
        singleServicePort.copy(requirePorts = true),
        servicePort = 80,
        hostPort = 80
      )
    }

    "generating (bridge network) app service port reports" should {
      val bridgeNetwork = BridgeNetwork()
      val container = Container.Mesos()

      behave like endpointsWithoutServicePorts(fakeApp(container = Option(container), networks = Seq(bridgeNetwork)))

      val singleServicePort = fakeApp(
        container = Option(
          container.copy(
            portMappings = Seq(Container.PortMapping(servicePort = 80, hostPort = Option(0)))
          )
        ),
        networks = Seq(bridgeNetwork)
      )

      behave like endpointsWithSingleDynamicServicePorts(singleServicePort)

      behave like endpointsWithSingleStaticServicePorts(
        singleServicePort.copy(
          requirePorts = true,
          container = singleServicePort.container.map { c =>
            c.copyWith(c.portMappings.map(_.copy(servicePort = 88, hostPort = Option(80))))
          }
        ),
        servicePort = 88,
        hostPort = 80
      )
    }

    "generating (container network) app service port reports" should {
      val containerNetwork = ContainerNetwork("macvlan")
      val containerNetwork2 = ContainerNetwork("weave")
      val container = Container.Mesos()
      val servicePort = 80

      val singleServicePort = fakeApp(
        container = Option(
          container.copy(
            portMappings = Seq(Container.PortMapping(servicePort = servicePort, hostPort = Option(0)))
          )
        ),
        networks = Seq(containerNetwork)
      )

      val appWithoutHostPorts = singleServicePort.copy(
        requirePorts = true,
        container = singleServicePort.container.map { c =>
          c.copyWith(c.portMappings.map(_.copy(servicePort = servicePort, containerPort = 8080, hostPort = None)))
        }
      )

      behave like endpointsWithoutServicePorts(fakeApp(container = Option(container), networks = Seq(containerNetwork)))

      behave like endpointsWithSingleDynamicServicePorts(singleServicePort)

      behave like endpointsWithSingleStaticServicePorts(
        singleServicePort.copy(
          requirePorts = true,
          container = singleServicePort.container.map { c =>
            c.copyWith(c.portMappings.map(_.copy(servicePort = servicePort, hostPort = Option(80))))
          }
        ),
        servicePort = servicePort,
        hostPort = 80
      )

      s"handle single instance with 1 (static) service port $servicePort and no host port by outputting the container ip and container port" in {
        val report = parseOutput(
          EndpointsHelper
            .appsToEndpointString(ListTasks(instances(appWithoutHostPorts, Seq(1)), Seq(appWithoutHostPorts)), allContainerNetworks)
        )
        val expected = List(("foo", servicePort.toString, Seq("1.1.1.11:8080")))
        report should equal(expected)
      }

      "handle multiple instances, different agents and no host port mapping by outputting the container ip and container port" in {
        val report = parseOutput(
          EndpointsHelper
            .appsToEndpointString(ListTasks(instances(appWithoutHostPorts, Seq(1, 1, 1)), Seq(appWithoutHostPorts)), allContainerNetworks)
        )
        val expected = List(("foo", "80", List("1.1.1.11:8080", "1.1.1.21:8080", "1.1.1.31:8080")))
        report should equal(expected)
      }

      "exclude container-networked endpoints when not included in the network list" in {
        val report = parseOutput(
          EndpointsHelper
            .appsToEndpointString(ListTasks(instances(appWithoutHostPorts, Seq(1)), Seq(appWithoutHostPorts)), noContainerNetworks)
        )
        val expected = List(("foo", servicePort.toString, Seq()))
        report should equal(expected)
      }

      "include only container-networked endpoints that are in the network list" in {
        val dualServicePorts = fakeApp(
          container = Option(
            container.copy(
              portMappings = Seq(
                Container.PortMapping(servicePort = 80, hostPort = None, containerPort = 8080, networkNames = Seq(containerNetwork.name)),
                Container.PortMapping(servicePort = 81, hostPort = None, containerPort = 8081, networkNames = Seq(containerNetwork2.name))
              )
            )
          ),
          networks = Seq(containerNetwork, containerNetwork2)
        ).copy(
          requirePorts = true
        )

        val report = parseOutput(
          EndpointsHelper
            .appsToEndpointString(ListTasks(instances(dualServicePorts, Seq(1)), Seq(dualServicePorts)), Set(containerNetwork.name))
        )
        inside(report) {
          case (app1, port1, mappings1) :: (app2, port2, mappings2) :: Nil =>
            app1 shouldBe "foo"
            port1 shouldBe "80"
            mappings1 shouldBe List("1.1.1.11:8080") // we include containerNetwork port mappings

            app2 shouldBe "foo"
            port2 shouldBe "81"
            mappings2 shouldBe List() // but not containerNetwork2 port mappings
        }
      }
    }
  }
}
