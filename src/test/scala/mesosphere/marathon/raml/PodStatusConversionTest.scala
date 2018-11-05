package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.health.{MesosCommandHealthCheck, MesosHttpHealthCheck, PortReference}
import mesosphere.marathon.core.instance.{Goal, Instance, Reservation}
import mesosphere.marathon.core.pod.{ContainerNetwork, MesosContainer, PodDefinition}
import mesosphere.marathon.core.task.state.NetworkInfoPlaceholder
import mesosphere.marathon.state.{PathId, Timestamp}
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.Protos

import scala.concurrent.duration._

class PodStatusConversionTest extends UnitTest {

  import PodStatusConversionTest._

  "PodStatusConversion" should {
    "multiple tasks with multiple container networks convert to proper network status" in {

      def fakeContainerNetworks(netmap: Map[String, String]): Seq[Protos.NetworkInfo] = netmap.map { entry =>
        val (name, ip) = entry
        Protos.NetworkInfo.newBuilder()
          .setName(name)
          .addIpAddresses(Protos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ip))
          .build()
      }(collection.breakOut)

      val tasksWithNetworks: Seq[core.task.Task] = Seq(
        fakeTask(fakeContainerNetworks(Map("abc" -> "1.2.3.4", "def" -> "5.6.7.8"))),
        fakeTask(fakeContainerNetworks(Map("abc" -> "1.2.3.4", "def" -> "5.6.7.8")))
      )
      val result: Seq[NetworkStatus] = networkStatuses(tasksWithNetworks)
      val expected: Seq[NetworkStatus] = Seq(
        NetworkStatus(name = Some("abc"), addresses = Seq("1.2.3.4")),
        NetworkStatus(name = Some("def"), addresses = Seq("5.6.7.8"))
      )
      result.size should be(expected.size)
      result.toSet should be(expected.toSet)
    }

    "multiple tasks with multiple host networks convert to proper network status" in {

      def fakeHostNetworks(ips: Seq[String]): Seq[Protos.NetworkInfo] = ips.map { ip =>
        Protos.NetworkInfo.newBuilder()
          .addIpAddresses(Protos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ip))
          .build()
      }(collection.breakOut)

      val tasksWithNetworks: Seq[core.task.Task] = Seq(
        fakeTask(fakeHostNetworks(Seq("1.2.3.4", "5.6.7.8"))),
        fakeTask(fakeHostNetworks(Seq("1.2.3.4", "5.6.7.8")))
      )
      val result: Seq[NetworkStatus] = networkStatuses(tasksWithNetworks)
      val expected: Seq[NetworkStatus] = Seq(
        // host network IPs are consolidated since they are nameless
        NetworkStatus(addresses = Seq("1.2.3.4", "5.6.7.8"))
      )
      result.size should be(expected.size)
      result should be(expected)
    }

    "ephemeral pod launched, no official Mesos status yet" in {
      implicit val clock = new SettableClock()
      val pod = basicOneContainerPod.copy(versionInfo = state.VersionInfo.OnlyVersion(clock.now()))

      clock += 1.seconds
      val fixture = provisionedInstance(pod)

      val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
      status.id should be(fixture.instance.instanceId.idString)
      status.specReference should be(Option(s"/v2/pods/foo::versions/${pod.version.toOffsetDateTime}"))
      status.agentHostname should be(Some("agent1"))
      status.agentId should be (Some("agentId1"))
      status.status should be(PodInstanceState.Pending)
      status.resources should be(Some(PodDefinition.DefaultExecutorResources))
      status.containers should be(Seq(
        ContainerStatus(
          name = "ct1",
          status = "TASK_STAGING",
          statusSince = fixture.since.toOffsetDateTime,
          containerId = Some(fixture.taskIds.head.idString),
          endpoints = Seq(
            ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
            ContainerEndpointStatus(name = "web")
          ),
          lastUpdated = fixture.since.toOffsetDateTime,
          lastChanged = fixture.since.toOffsetDateTime
        )
      ))
    }

    "ephemeral pod launched, received STAGING status from Mesos" in {
      implicit val clock = new SettableClock()
      val pod = basicOneContainerPod.copy(versionInfo = state.VersionInfo.OnlyVersion(clock.now()))

      clock += 1.seconds
      val fixture = stagingInstance(pod)

      val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
      status.id should be(fixture.instance.instanceId.idString)
      status.agentHostname should be(Some("agent1"))
      status.agentId should be (Some("agentId1"))
      status.status should be(PodInstanceState.Staging)
      status.resources should be(Some(pod.aggregateResources()))
      status.containers should be(Seq(
        ContainerStatus(
          name = "ct1",
          status = "TASK_STAGING",
          statusSince = fixture.since.toOffsetDateTime,
          containerId = Some(fixture.taskIds.head.idString),
          endpoints = Seq(
            ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
            ContainerEndpointStatus(name = "web")
          ),
          resources = pod.container("ct1").map(_.resources),
          lastUpdated = fixture.since.toOffsetDateTime,
          lastChanged = fixture.since.toOffsetDateTime
        )
      ))
      status.networks should be('empty)
    }

    "ephemeral pod launched, received STARTING status from Mesos" in {
      implicit val clock = new SettableClock()
      val pod = basicOneContainerPod.copy(versionInfo = state.VersionInfo.OnlyVersion(clock.now()))

      clock += 1.seconds
      val fixture = startingInstance(pod)

      val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
      status.id should be(fixture.instance.instanceId.idString)
      status.agentHostname should be(Some("agent1"))
      status.agentId should be (Some("agentId1"))
      status.status should be(PodInstanceState.Staging)
      status.resources should be(Some(pod.aggregateResources()))
      status.containers should be(Seq(
        ContainerStatus(
          name = "ct1",
          status = "TASK_STARTING",
          statusSince = fixture.since.toOffsetDateTime,
          containerId = Some(fixture.taskIds.head.idString),
          endpoints = Seq(
            ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
            ContainerEndpointStatus(name = "web")
          ),
          resources = pod.container("ct1").map(_.resources),
          lastUpdated = fixture.since.toOffsetDateTime,
          lastChanged = fixture.since.toOffsetDateTime
        )
      ))
      status.networks.toSet should be(Set(
        NetworkStatus(Some("dcos"), Seq("1.2.3.4")),
        NetworkStatus(Some("bigdog"), Seq("2.3.4.5"))
      ))
    }

    "ephemeral pod launched, received RUNNING status from Mesos, no task endpoint health info" in {
      implicit val clock = new SettableClock()
      val pod = basicOneContainerPod.copy(versionInfo = state.VersionInfo.OnlyVersion(clock.now()))

      clock += 1.seconds
      val fixture = runningInstance(pod)

      val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
      status.id should be(fixture.instance.instanceId.idString)
      status.agentHostname should be(Some("agent1"))
      status.agentId should be (Some("agentId1"))
      status.status should be(PodInstanceState.Degraded)
      status.resources should be(Some(pod.aggregateResources()))
      status.containers should be(Seq(
        ContainerStatus(
          name = "ct1",
          status = "TASK_RUNNING",
          statusSince = fixture.since.toOffsetDateTime,
          containerId = Some(fixture.taskIds.head.idString),
          conditions = Seq(
            StatusCondition("healthy", fixture.since.toOffsetDateTime, fixture.since.toOffsetDateTime, "false",
              Some(PodStatusConversion.HEALTH_UNREPORTED))
          ),
          endpoints = Seq(
            ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
            ContainerEndpointStatus(name = "web")
          ),
          resources = pod.container("ct1").map(_.resources),
          lastUpdated = fixture.since.toOffsetDateTime,
          lastChanged = fixture.since.toOffsetDateTime
        )
      ))
      status.networks.toSet should be(Set(
        NetworkStatus(Some("dcos"), Seq("1.2.3.4")),
        NetworkStatus(Some("bigdog"), Seq("2.3.4.5"))
      ))
    }

    "ephemeral pod launched, received RUNNING status from Mesos, task endpoint health is failing" in {
      implicit val clock = new SettableClock()
      val pod = basicOneContainerPod.copy(versionInfo = state.VersionInfo.OnlyVersion(clock.now()))

      clock += 1.seconds
      val fixture = runningInstance(pod = pod, maybeHealthy = Some(false)) // task status will say unhealthy

      val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
      status.id should be(fixture.instance.instanceId.idString)
      status.agentHostname should be(Some("agent1"))
      status.agentId should be (Some("agentId1"))
      status.status should be(PodInstanceState.Degraded)
      status.resources should be(Some(pod.aggregateResources()))
      status.containers should be(Seq(
        ContainerStatus(
          name = "ct1",
          status = "TASK_RUNNING",
          statusSince = fixture.since.toOffsetDateTime,
          containerId = Some(fixture.taskIds.head.idString),
          conditions = Seq(
            StatusCondition("healthy", fixture.since.toOffsetDateTime, fixture.since.toOffsetDateTime, "false",
              Some(PodStatusConversion.HEALTH_REPORTED))
          ),
          endpoints = Seq(
            ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
            ContainerEndpointStatus(name = "web", healthy = Some(false))
          ),
          resources = pod.container("ct1").map(_.resources),
          lastUpdated = fixture.since.toOffsetDateTime,
          lastChanged = fixture.since.toOffsetDateTime
        )
      ))
      status.networks.toSet should be(Set(
        NetworkStatus(Some("dcos"), Seq("1.2.3.4")),
        NetworkStatus(Some("bigdog"), Seq("2.3.4.5"))
      ))
    }

    "ephemeral pod launched, received RUNNING status from Mesos, task endpoint health looks great" in {
      implicit val clock = new SettableClock()
      val pod = basicOneContainerPod.copy(versionInfo = state.VersionInfo.OnlyVersion(clock.now()))

      clock += 1.seconds
      val fixture = runningInstance(pod = pod, maybeHealthy = Some(true)) // task status will say healthy

      val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
      status.id should be(fixture.instance.instanceId.idString)
      status.agentHostname should be(Some("agent1"))
      status.agentId should be (Some("agentId1"))
      status.status should be(PodInstanceState.Stable)
      status.resources should be(Some(pod.aggregateResources()))
      status.containers should be(Seq(
        ContainerStatus(
          name = "ct1",
          status = "TASK_RUNNING",
          statusSince = fixture.since.toOffsetDateTime,
          containerId = Some(fixture.taskIds.head.idString),
          conditions = Seq(
            StatusCondition("healthy", fixture.since.toOffsetDateTime, fixture.since.toOffsetDateTime, "true",
              Some(PodStatusConversion.HEALTH_REPORTED))
          ),
          endpoints = Seq(
            ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
            ContainerEndpointStatus(name = "web", healthy = Some(true))
          ),
          resources = pod.container("ct1").map(_.resources),
          lastUpdated = fixture.since.toOffsetDateTime,
          lastChanged = fixture.since.toOffsetDateTime
        )
      ))
      status.networks.toSet should be(Set(
        NetworkStatus(Some("dcos"), Seq("1.2.3.4")),
        NetworkStatus(Some("bigdog"), Seq("2.3.4.5"))
      ))
    }

    "ephemeral pod launched, received RUNNING status from Mesos, task command-line health is missing" in {
      implicit val clock = new SettableClock()

      val pod = withCommandLineHealthChecks(basicOneContainerPod.copy(versionInfo = state.VersionInfo.OnlyVersion(clock.now())))

      clock += 1.seconds
      val fixture = runningInstance(pod = pod) // mesos task status health is missing

      val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
      status.id should be(fixture.instance.instanceId.idString)
      status.agentHostname should be(Some("agent1"))
      status.agentId should be (Some("agentId1"))
      status.status should be(PodInstanceState.Degraded)
      status.resources should be(Some(pod.aggregateResources()))
      status.containers should be(Seq(
        ContainerStatus(
          name = "ct1",
          status = "TASK_RUNNING",
          statusSince = fixture.since.toOffsetDateTime,
          containerId = Some(fixture.taskIds.head.idString),
          conditions = Seq(
            StatusCondition("healthy", fixture.since.toOffsetDateTime, fixture.since.toOffsetDateTime, "false",
              Some(PodStatusConversion.HEALTH_UNREPORTED))
          ),
          endpoints = Seq(
            ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
            ContainerEndpointStatus(name = "web")
          ),
          resources = pod.container("ct1").map(_.resources),
          lastUpdated = fixture.since.toOffsetDateTime,
          lastChanged = fixture.since.toOffsetDateTime
        )
      ))
      status.networks.toSet should be(Set(
        NetworkStatus(Some("dcos"), Seq("1.2.3.4")),
        NetworkStatus(Some("bigdog"), Seq("2.3.4.5"))
      ))
    }

    "ephemeral pod launched, received RUNNING status from Mesos, task command-line health is failing" in {
      implicit val clock = new SettableClock()

      val pod = withCommandLineHealthChecks(basicOneContainerPod.copy(versionInfo = state.VersionInfo.OnlyVersion(clock.now())))

      clock += 1.seconds
      val fixture = runningInstance(pod = pod, maybeHealthy = Some(false)) // task status will say unhealthy

      val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
      status.id should be(fixture.instance.instanceId.idString)
      status.agentHostname should be(Some("agent1"))
      status.agentId should be (Some("agentId1"))
      status.status should be(PodInstanceState.Degraded)
      status.resources should be(Some(pod.aggregateResources()))
      status.containers should be(Seq(
        ContainerStatus(
          name = "ct1",
          status = "TASK_RUNNING",
          statusSince = fixture.since.toOffsetDateTime,
          containerId = Some(fixture.taskIds.head.idString),
          conditions = Seq(
            StatusCondition("healthy", fixture.since.toOffsetDateTime, fixture.since.toOffsetDateTime, "false",
              Some(PodStatusConversion.HEALTH_REPORTED))
          ),
          endpoints = Seq(
            ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
            ContainerEndpointStatus(name = "web")
          ),
          resources = pod.container("ct1").map(_.resources),
          lastUpdated = fixture.since.toOffsetDateTime,
          lastChanged = fixture.since.toOffsetDateTime
        )
      ))
      status.networks.toSet should be(Set(
        NetworkStatus(Some("dcos"), Seq("1.2.3.4")),
        NetworkStatus(Some("bigdog"), Seq("2.3.4.5"))
      ))
    }

    "ephemeral pod launched, received RUNNING status from Mesos, task command-line health is passing" in {
      implicit val clock = new SettableClock()

      val pod = withCommandLineHealthChecks(basicOneContainerPod.copy(versionInfo = state.VersionInfo.OnlyVersion(clock.now())))

      clock += 1.seconds
      val fixture = runningInstance(pod = pod, maybeHealthy = Some(true)) // task status will say healthy

      val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, fixture.instance))
      status.id should be(fixture.instance.instanceId.idString)
      status.agentHostname should be(Some("agent1"))
      status.agentId should be (Some("agentId1"))
      status.status should be(PodInstanceState.Stable)
      status.resources should be(Some(pod.aggregateResources()))
      status.containers should be(Seq(
        ContainerStatus(
          name = "ct1",
          status = "TASK_RUNNING",
          statusSince = fixture.since.toOffsetDateTime,
          containerId = Some(fixture.taskIds.head.idString),
          conditions = Seq(
            StatusCondition("healthy", fixture.since.toOffsetDateTime, fixture.since.toOffsetDateTime, "true",
              Some(PodStatusConversion.HEALTH_REPORTED))
          ),
          endpoints = Seq(
            ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
            ContainerEndpointStatus(name = "web")
          ),
          resources = pod.container("ct1").map(_.resources),
          lastUpdated = fixture.since.toOffsetDateTime,
          lastChanged = fixture.since.toOffsetDateTime
        )
      ))
      status.networks.toSet should be(Set(
        NetworkStatus(Some("dcos"), Seq("1.2.3.4")),
        NetworkStatus(Some("bigdog"), Seq("2.3.4.5"))
      ))
    }

    "a stateful pod with one container and one persistent volume" in {
      val localVolumeId = core.instance.LocalVolumeId(
        PathId("/persistent"), "volume", "5425cbaa-8fd3-45f0-afa4-74ef4fcc594b")
      val reservation = core.instance.Reservation(
        volumeIds = Seq(localVolumeId),
        state = core.instance.Reservation.State.New(timeout = None))

      implicit val clock = new SettableClock()
      val fixture = fakeInstance(
        podWithPersistentVolume, core.condition.Condition.Running, core.condition.Condition.Running,
        maybeReservation = Some(reservation))

      val status = PodStatusConversion.podInstanceStatusRamlWriter((podWithPersistentVolume, fixture.instance))
      status.localVolumes should be(Seq(
        LocalVolumeId(
          "/persistent", "volume", "5425cbaa-8fd3-45f0-afa4-74ef4fcc594b",
          "persistent#volume#5425cbaa-8fd3-45f0-afa4-74ef4fcc594b")))
    }

  }
}

object PodStatusConversionTest {

  val containerResources = Resources(cpus = 0.01, mem = 100)

  val basicOneContainerPod = PodDefinition(
    id = PathId("/foo"),
    containers = Seq(
      MesosContainer(
        name = "ct1",
        resources = containerResources,
        image = Some(Image(kind = ImageType.Docker, id = "busybox")),
        endpoints = Seq(
          Endpoint(name = "web", containerPort = Some(80)),
          Endpoint(name = "admin", containerPort = Some(90), hostPort = Some(0))
        ),
        healthCheck = Some(MesosHttpHealthCheck(portIndex = Some(PortReference("web")), path = Some("/ping")))
      )
    ),
    networks = Seq(ContainerNetwork(name = "dcos"), ContainerNetwork("bigdog"))
  )

  val podWithPersistentVolume = PodDefinition(
    id = PathId("/persistent"),
    containers = Seq(
      MesosContainer(
        name = "ct1",
        resources = containerResources,
        image = Some(Image(kind = ImageType.Docker, id = "busybox")),
        volumeMounts = Seq(state.VolumeMount(Some("data"), "mountPath")))),
    volumes = Seq(
      state.PersistentVolume(
        name = Some("volume"),
        persistent = state.PersistentVolumeInfo(10))))

  case class InstanceFixture(
      since: Timestamp,
      agentInfo: core.instance.Instance.AgentInfo,
      taskIds: Seq[core.task.Task.Id],
      instance: core.instance.Instance)

  def provisionedInstance(pod: PodDefinition)(implicit clock: SettableClock): InstanceFixture =
    fakeInstance(pod, core.condition.Condition.Provisioned, core.condition.Condition.Provisioned)

  def stagingInstance(pod: PodDefinition)(implicit clock: SettableClock): InstanceFixture =
    fakeInstance(pod, core.condition.Condition.Staging, core.condition.Condition.Staging, Some(Protos.TaskState.TASK_STAGING))

  def startingInstance(pod: PodDefinition)(implicit clock: SettableClock): InstanceFixture =
    fakeInstance(pod, core.condition.Condition.Starting, core.condition.Condition.Starting, Some(Protos.TaskState.TASK_STARTING),
      Some(Map("dcos" -> "1.2.3.4", "bigdog" -> "2.3.4.5")))

  def runningInstance(
    pod: PodDefinition,
    maybeHealthy: Option[Boolean] = None)(implicit clock: SettableClock): InstanceFixture =

    fakeInstance(pod, core.condition.Condition.Running, core.condition.Condition.Running, Some(Protos.TaskState.TASK_RUNNING),
      Some(Map("dcos" -> "1.2.3.4", "bigdog" -> "2.3.4.5")), maybeHealthy)

  def fakeInstance(
    pod: PodDefinition,
    condition: core.condition.Condition,
    taskStatus: core.condition.Condition,
    maybeTaskState: Option[Protos.TaskState] = None,
    maybeNetworks: Option[Map[String, String]] = None,
    maybeHealthy: Option[Boolean] = None,
    maybeReservation: Option[Reservation] = None)(implicit clock: SettableClock): InstanceFixture = {

    val since = clock.now()
    val agentInfo = core.instance.Instance.AgentInfo("agent1", Some("agentId1"), None, None, Seq.empty)
    val instanceId = core.instance.Instance.Id.forRunSpec(pod.id)
    val taskIds = pod.containers.map { container =>
      core.task.Task.Id.forInstanceId(instanceId, Some(container))
    }

    val mesosStatus = maybeTaskState.map { taskState =>
      val statusProto = Protos.TaskStatus.newBuilder()
        .setState(taskState)
        .setTaskId(taskIds.head.mesosTaskId)

      maybeNetworks.foreach { networks =>
        statusProto.setContainerStatus(Protos.ContainerStatus.newBuilder()
          .addAllNetworkInfos(networks.map { entry =>
            val (networkName, ipAddress) = entry
            Protos.NetworkInfo.newBuilder().addIpAddresses(
              Protos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ipAddress)
            ).setName(networkName).build()
          }.asJava).build()
        ).build()
      }

      maybeHealthy.foreach(statusProto.setHealthy)
      statusProto.build()
    }

    val instance: core.instance.Instance = core.instance.Instance(
      instanceId = instanceId,
      agentInfo = Some(agentInfo),
      state = core.instance.Instance.InstanceState(
        condition = condition,
        since = since,
        activeSince = if (condition == core.condition.Condition.Provisioned) None else Some(since),
        healthy = None,
        goal = Goal.Running),
      tasksMap = Seq[core.task.Task](
        core.task.Task(
          taskIds.head,
          since,
          core.task.Task.Status(
            stagedAt = since,
            startedAt = if (taskStatus == core.condition.Condition.Provisioned) None else Some(since),
            mesosStatus = mesosStatus,
            condition = taskStatus,
            networkInfo = NetworkInfoPlaceholder(hostPorts = Seq(1001))
          )
        )
      ).map(t => t.taskId -> t)(collection.breakOut),
      runSpecVersion = pod.version,
      unreachableStrategy = state.UnreachableStrategy.default(),
      reservation = maybeReservation
    )

    InstanceFixture(since, agentInfo, taskIds, instance)
  } // fakeInstance

  def fakeTask(networks: Seq[Protos.NetworkInfo]) = {
    val instanceId = Instance.Id.forRunSpec(PathId.empty)
    val taskId = core.task.Task.Id.forInstanceId(instanceId)
    core.task.Task(
      taskId = taskId,
      status = core.task.Task.Status(
        stagedAt = Timestamp.zero,
        mesosStatus = Some(Protos.TaskStatus.newBuilder()
          .setTaskId(taskId.mesosTaskId)
          .setState(Protos.TaskState.TASK_UNKNOWN)
          .setContainerStatus(Protos.ContainerStatus.newBuilder()
            .addAllNetworkInfos(networks.asJava).build())
          .build()),
        condition = core.condition.Condition.Finished,
        networkInfo = NetworkInfoPlaceholder()
      ),
      runSpecVersion = Timestamp.zero)
  }

  def withCommandLineHealthChecks(pod: PodDefinition): PodDefinition = pod.copy(
    // swap any endpoint health checks for a command-line health check
    containers = basicOneContainerPod.containers.map { ct =>
      ct.copy(
        healthCheck = Some(MesosCommandHealthCheck(command = state.Command("echo this is a health check command"))))
    })
}
