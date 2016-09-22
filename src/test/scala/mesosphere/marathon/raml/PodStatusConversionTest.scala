package mesosphere.marathon.raml

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.pod.{ ContainerNetwork, MesosContainer, PodDefinition }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import org.scalatest.Matchers

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class PodStatusConversionTest extends MarathonSpec with Matchers {

  test("ephemeral pod launched, no official Mesos status yet") {
    val clock = ConstantClock()
    val version = clock.now()
    val pod = PodDefinition(
      id = PathId("/foo"),
      containers = Seq(
        MesosContainer(
          name = "ct1",
          resources = Resources(cpus = 0.01, mem = 100),
          image = Some(Image(kind = ImageType.Docker, id = "busybox")),
          endpoints = Seq(
            Endpoint(name = "web", containerPort = Some(80)),
            Endpoint(name = "admin", containerPort = Some(90), hostPort = Some(0))
          ),
          healthCheck = Some(HealthCheck(http = Some(HttpHealthCheck(endpoint = "web", path = Some("/ping")))))
        )
      ),
      networks = Seq(ContainerNetwork(name = "dcos")),
      version = version
    )

    clock += 1.seconds
    val since = clock.now()

    val agentInfo = Instance.AgentInfo("agent1", None, Seq.empty)
    val instanceId = Instance.Id.forRunSpec(pod.id)
    val taskId = Task.Id.forInstanceId(instanceId, pod.containers.find(_.name == "ct1"))
    val instance: Instance = Instance(
      instanceId = instanceId,
      agentInfo = agentInfo,
      state = Instance.InstanceState(
        status = InstanceStatus.Created,
        since = since,
        version = version,
        healthy = None),
      tasksMap = Seq[Task](
        Task.LaunchedEphemeral(
          taskId,
          agentInfo,
          since,
          Task.Status(
            stagedAt = since,
            startedAt = None,
            mesosStatus = None,
            taskStatus = InstanceStatus.Created
          ),
          hostPorts = Seq(1001)
        )
      ).map(t => t.taskId -> t).toMap)

    val status = PodStatusConversion.podInstanceStatusRamlWriter((pod, instance))
    status.id should be(instanceId.idString)
    status.agentHostname should be(Some("agent1"))
    status.status should be(PodInstanceState.Pending)
    status.containers should be(Seq(
      ContainerStatus(
        name = "ct1",
        status = "TASK_STAGING",
        statusSince = since.toOffsetDateTime,
        containerId = Some(taskId.idString),
        endpoints = Seq(
          ContainerEndpointStatus(name = "admin", allocatedHostPort = Some(1001)),
          ContainerEndpointStatus(name = "web")
        ),
        lastUpdated = since.toOffsetDateTime,
        lastChanged = since.toOffsetDateTime
      )
    ))
  }
}
