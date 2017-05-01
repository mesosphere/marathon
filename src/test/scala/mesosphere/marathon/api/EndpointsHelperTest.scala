package mesosphere.marathon
package api

import mesosphere.UnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork, HostNetwork, Network }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.core.task.tracker.InstanceTracker.{ InstancesBySpec, SpecInstances }
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => Mesos }

class EndpointsHelperTest extends UnitTest {

  def instances(app: AppDefinition, taskCounts: Seq[Int]): InstancesBySpec = {
    val instances = SpecInstances(app.id, taskCounts.zipWithIndex.flatMap {
      case (numTasks, agentIndex) =>
        val agentId = agentIndex + 1
        val hostname = s"agent$agentId"
        val agent = AgentInfo(hostname, None, Nil)

        1.to(numTasks).map { taskIndex =>
          val instanceId = Instance.Id.forRunSpec(app.id)
          val ipAddresses: Seq[Mesos.NetworkInfo.IPAddress] =
            if (app.networks.hasNonHostNetworking) Nil
            else Seq(Mesos.NetworkInfo.IPAddress.newBuilder()
              .setIpAddress(s"1.1.1.${agentId * 10 + taskIndex}")
              .setProtocol(Mesos.NetworkInfo.Protocol.IPv4)
              .build)
          val hostPorts = app.hostPorts.zipWithIndex.flatMap {
            case (None, _) => None
            case (Some(_), i) if !app.requirePorts => Option(1000 + (taskIndex * 10) + i)
            case (portOption, _) => portOption
          }
          val taskId = Task.Id.forInstanceId(instanceId, None)
          val task = Task.LaunchedEphemeral(taskId, runSpecVersion = Timestamp.zero, status = Task.Status(
            stagedAt = Timestamp.zero, startedAt = None, mesosStatus = None, condition = Condition.Running,
            networkInfo = NetworkInfo(hostname, hostPorts = hostPorts, ipAddresses = ipAddresses)
          ))
          val state = Instance.InstanceState(
            condition = Condition.Running, since = Timestamp.zero, activeSince = None, healthy = None)
          instanceId -> Instance(instanceId, agent,
            state = state, tasksMap = Map(taskId -> task), Timestamp.zero, UnreachableStrategy.default())
        }
    }.toMap
    )
    InstancesBySpec(Map(app.id -> instances))
  }

  def fakeApp(appId: PathId = PathId("/foo"), container: => Option[Container] = None, network: => Network): AppDefinition =
    AppDefinition(appId, cmd = Option("sleep"), container = container, networks = Seq(network))

  def endpointsWithoutServicePorts(app: AppDefinition): Unit = {
    "handle single instance without service ports" in {
      val report = EndpointsHelper.appsToEndpointString(instances(app, Seq(1)), Seq(app))
      val expected = "foo\t \tagent1\t\n"
      report should equal(expected)
    }
    "handle multiple instances, same agent, without service ports" in {
      val report = EndpointsHelper.appsToEndpointString(instances(app, Seq(2)), Seq(app))
      val expected = "foo\t \tagent1\tagent1\t\n"
      report should equal(expected)
    }
    "handle multiple instances, different agents, without service ports" in {
      val report = EndpointsHelper.appsToEndpointString(instances(app, Seq(2, 1, 2)), Seq(app))
      val expected = "foo\t \tagent1\tagent1\tagent2\tagent3\tagent3\t\n"
      report should equal(expected)
    }
  }

  def endpointsWithSingleDynamicServicePorts(app: AppDefinition): Unit = {
    "handle single instance with 1 service port" in {
      val report = EndpointsHelper.appsToEndpointString(instances(app, Seq(1)), Seq(app))
      val expected = "foo\t80\tagent1:1010\t\n"
      report should equal(expected)
    }
    "handle multiple instances, same agent, with 1 service port" in {
      val report = EndpointsHelper.appsToEndpointString(instances(app, Seq(2)), Seq(app))
      val expected = "foo\t80\tagent1:1010\tagent1:1020\t\n"
      report should equal(expected)
    }
    "handle multiple instances, different agents, with 1 service port" in {
      val report = EndpointsHelper.appsToEndpointString(instances(app, Seq(2, 1, 2)), Seq(app))
      val expected = "foo\t80\tagent1:1010\tagent1:1020\tagent2:1010\tagent3:1010\tagent3:1020\t\n"
      report should equal(expected)
    }
  }

  def endpointsWithSingleStaticServicePorts(app: AppDefinition, servicePort: Int, hostPort: Int): Unit = {
    s"handle single instance with 1 (static) service port $servicePort and host port $hostPort" in {
      val report = EndpointsHelper.appsToEndpointString(instances(app, Seq(1)), Seq(app))
      val expected = s"foo\t$servicePort\tagent1:$hostPort\t\n"
      report should equal(expected)
    }
    s"handle multiple instances, different agents, with 1 (static) service port $servicePort and host port $hostPort" in {
      val report = EndpointsHelper.appsToEndpointString(instances(app, Seq(1, 1, 1)), Seq(app))
      val expected = s"foo\t$servicePort\tagent1:$hostPort\tagent2:$hostPort\tagent3:$hostPort\t\n"
      report should equal(expected)
    }
  }

  "EndpointsHelper" when {
    "generating (host network) app service port reports" should {
      behave like endpointsWithoutServicePorts(fakeApp(network = HostNetwork))

      val singleServicePort = fakeApp(network = HostNetwork).copy(portDefinitions = PortDefinitions(80))

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

      behave like endpointsWithoutServicePorts(
        fakeApp(container = Option(container), network = bridgeNetwork))

      val singleServicePort = fakeApp(container = Option(container.copy(
        portMappings = Seq(Container.PortMapping(servicePort = 80, hostPort = Option(0)))
      )), network = bridgeNetwork)

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
      val containerNetwork = ContainerNetwork("whatever")
      val container = Container.Mesos()

      behave like endpointsWithoutServicePorts(
        fakeApp(container = Option(container), network = containerNetwork))

      val singleServicePort = fakeApp(container = Option(container.copy(
        portMappings = Seq(Container.PortMapping(servicePort = 80, hostPort = Option(0)))
      )), network = containerNetwork)

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

      behave like endpointsWithSingleStaticServicePorts(
        singleServicePort.copy(
          requirePorts = true,
          container = singleServicePort.container.map { c =>
            c.copyWith(c.portMappings.map(_.copy(servicePort = 88, hostPort = None)))
          }
        ),
        servicePort = 88,
        hostPort = 0 // weird edge case for tasks on container networks without hostPort defined
      )
    }
  }
}
