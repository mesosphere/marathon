package mesosphere.mesos

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.serialization.SecretSerializer
import mesosphere.marathon.core.health.{MesosCommandHealthCheck, MesosHealthCheck}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.raml
import mesosphere.marathon.raml.Endpoint
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.tasks.PortsMatch
import mesosphere.mesos.protos.Implicits._
import org.apache.mesos.Protos.{DurationInfo, KillPolicy}
import org.apache.mesos.{Protos => mesos}
import java.util.concurrent.TimeUnit.SECONDS

import mesosphere.marathon.core.task.state.NetworkInfo

import scala.collection.immutable.Seq

object TaskGroupBuilder extends StrictLogging {

  // These labels are necessary for AppC images to work.
  // Given that Docker only works under linux with 64bit,
  // let's (for now) set these values to reflect that.
  protected[mesos] val LinuxAmd64 = Map("os" -> "linux", "arch" -> "amd64")

  private val ephemeralVolumePathPrefix = "volumes/"

  case class BuilderConfig(
      acceptedResourceRoles: Set[String],
      envVarsPrefix: Option[String],
      mesosBridgeName: String)

  def build(
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    instanceId: Instance.Id,
    taskIds: Seq[Task.Id],
    config: BuilderConfig,
    runSpecTaskProcessor: RunSpecTaskProcessor,
    resourceMatch: ResourceMatcher.ResourceMatch,
    volumeMatchOption: Option[PersistentVolumeMatcher.VolumeMatch]
  ): (mesos.ExecutorInfo, mesos.TaskGroupInfo, Map[Task.Id, NetworkInfo]) = {
    val packedResources = binPackResources(podDefinition, resourceMatch.resources)

    val allEndpoints = podDefinition.containers.flatMap(_.endpoints)

    val mesosNetworks = buildMesosNetworks(
      podDefinition.networks, allEndpoints, resourceMatch.hostPorts, config.mesosBridgeName)

    val executorInfo = computeExecutorInfo(
      podDefinition,
      packedResources,
      resourceMatch.portsMatch,
      mesosNetworks,
      volumeMatchOption,
      instanceId,
      offer.getFrameworkId)

    val endpointAllocationsPerContainer: Map[String, Seq[Endpoint]] =
      podDefinition.containers.flatMap { c =>
        c.endpoints.map(c.name -> _)
      }.zip(resourceMatch.hostPorts).groupBy { case (t, _) => t._1 }.map {
        case (k, s) =>
          k -> s.map { case (t, hp) => t._2.copy(hostPort = hp) }
      }

    val taskGroup = mesos.TaskGroupInfo.newBuilder.addAllTasks {
      podDefinition.containers.zip(taskIds).map {
        case (container, taskId) =>
          val endpoints = endpointAllocationsPerContainer.getOrElse(container.name, Nil)
          val portAssignments = computePortAssignments(podDefinition, endpoints)

          val task = computeTaskInfo(container, podDefinition, offer, instanceId, taskId,
            packedResources, resourceMatch.hostPorts, config, portAssignments)
            .setDiscovery(taskDiscovery(podDefinition, endpoints))
          task.build
      }.asJava
    }

    // call all configured run spec customizers here (plugin)
    runSpecTaskProcessor.taskGroup(podDefinition, executorInfo, taskGroup)

    val networkInfos = buildTaskNetworkInfos(podDefinition, offer.getHostname, taskIds, resourceMatch.hostPorts)

    (executorInfo.build, taskGroup.build, networkInfos)
  }

  // This method just double-checks that the matched resources are exactly
  // what is needed and in right quantities.
  private[this] def verifyMatchedResources(pod: PodDefinition, matchedResources: Seq[mesos.Resource]): Unit = {
    val cpus = BigDecimal(pod.executorResources.cpus) + pod.containers.map(c => BigDecimal(c.resources.cpus)).sum
    val mem = BigDecimal(pod.executorResources.mem) + pod.containers.map(c => BigDecimal(c.resources.mem)).sum
    val disk = BigDecimal(pod.executorResources.disk) + pod.containers.map(c => BigDecimal(c.resources.disk)).sum
    val gpus = BigDecimal(pod.executorResources.gpus) + pod.containers.map(c => BigDecimal(c.resources.gpus)).sum

    val matchedCpus = matchedResources.filter(_.getName == "cpus").map(c => BigDecimal(c.getScalar.getValue)).sum
    val matchedMem = matchedResources.filter(_.getName == "mem").map(c => BigDecimal(c.getScalar.getValue)).sum
    val matchedDisk = matchedResources.filter(_.getName == "disk").map(c => BigDecimal(c.getScalar.getValue)).sum
    val matchedGpus = matchedResources.filter(_.getName == "gpus").map(c => BigDecimal(c.getScalar.getValue)).sum

    assert(cpus <= matchedCpus)
    assert(mem <= matchedMem)
    assert(disk <= matchedDisk)
    assert(gpus <= matchedGpus)
  }

  // We have list of (BigDecimal, Resource) pairs in order to avoid using == and != operators with doubles.
  private case class Resources(
      cpus: List[(BigDecimal, mesos.Resource)],
      mem: List[(BigDecimal, mesos.Resource)],
      disk: List[(BigDecimal, mesos.Resource)],
      gpus: List[(BigDecimal, mesos.Resource)])

  private class ResourceConsumer(initial: Resources) {
    private var cpus = initial.cpus
    private var mem = initial.mem
    private var disk = initial.disk
    private var gpus = initial.gpus

    // Find a CPU resource for the given quantity if possible, and remove the resource from the resource list.
    def consumeCpus(quantity: Double): Option[mesos.Resource] = {
      consume(cpus, BigDecimal(quantity)).map {
        case (leftResources, consumedResource) =>
          cpus = leftResources
          consumedResource
      }
    }

    // Find a MEM resource for the given quantity if possible, and remove the resource from the resource list.
    def consumeMem(quantity: Double): Option[mesos.Resource] = {
      consume(mem, BigDecimal(quantity)).map {
        case (leftResources, consumedResource) =>
          mem = leftResources
          consumedResource
      }
    }

    // Find a DISK resource for the given quantity if possible, and remove the resource from the resource list.
    def consumeDisk(quantity: Double): Option[mesos.Resource] = {
      consume(disk, BigDecimal(quantity)) map {
        case (leftResources, consumedResource) =>
          disk = leftResources
          consumedResource
      }
    }

    // Find a GPU resource for the given quantity if possible, and remove the resource from the resource list.
    def consumeGpus(quantity: Double): Option[mesos.Resource] = {
      consume(gpus, BigDecimal(quantity)).map {
        case (leftResources, consumedResource) =>
          gpus = leftResources
          consumedResource
      }
    }

    // Find a resource with exactly the given quantity among the provided resources.
    private def consume(
      resources: List[(BigDecimal, mesos.Resource)],
      quantity: BigDecimal): Option[(List[(BigDecimal, mesos.Resource)], mesos.Resource)] =
      if (quantity <= BigDecimal(0)) {
        None
      } else {
        resources.partition {
          case (resourceQuantity, _) => resourceQuantity != quantity
        } match {
          case (_, Nil) =>
            throw new IllegalStateException("failed to find a resource with the given quantity")
          case (left, pair :: right) =>
            // return a new resource list not containing the found resource and the found resource itself
            Some((left ::: right, pair._2))
        }
      }
  }

  private[this] def binPackResources(pod: PodDefinition, matchedResources: Seq[mesos.Resource]): Resources = {
    verifyMatchedResources(pod, matchedResources)

    val decimalZero = BigDecimal(0)
    val cpuQuantities = {
      BigDecimal(pod.executorResources.cpus) :: pod.containers.map(c => BigDecimal(c.resources.cpus)).toList
    }.filter(_ > decimalZero)
    val memQuantities = {
      BigDecimal(pod.executorResources.mem) :: pod.containers.map(c => BigDecimal(c.resources.mem)).toList
    }.filter(_ > decimalZero)
    val diskQuantities = {
      BigDecimal(pod.executorResources.disk) :: pod.containers.map(c => BigDecimal(c.resources.disk)).toList
    }.filter(_ > decimalZero)
    val gpuQuantities = {
      BigDecimal(pod.executorResources.gpus) :: pod.containers.map(c => BigDecimal(c.resources.gpus)).toList
    }.filter(_ > decimalZero)

    val cpuResources = matchedResources.filter(_.getName == "cpus").toList
    val memResources = matchedResources.filter(_.getName == "mem").toList
    val diskResources = matchedResources.filter(_.getName == "disk").toList
    val gpuResources = matchedResources.filter(_.getName == "gpus").toList

    val packedCpus = binPackResources(cpuQuantities, cpuResources)
    val packedMem = binPackResources(memQuantities, memResources)
    val packedDisk = binPackResources(diskQuantities, diskResources)
    val packedGpus = binPackResources(gpuQuantities, gpuResources)

    Resources(cpus = packedCpus, mem = packedMem, disk = packedDisk, gpus = packedGpus)
  }

  private[this] def binPackResources(
    quantities: List[BigDecimal],
    resources: List[mesos.Resource]): List[(BigDecimal, mesos.Resource)] = {

    // The number of possible assignments that can be considered here,
    // is exponential to the number of quantities and the number of matched resources,
    // but since both numbers are not going to be large, it is fine not to do any
    // premature optimization at the moment.
    def assignResourceQuantities(left: List[BigDecimal], residual: Map[Int, BigDecimal],
      placed: Map[Int, List[BigDecimal]]): Option[Map[Int, List[BigDecimal]]] = {
      left match {
        case Nil =>
          Some(placed)
        case head :: tail =>
          residual.foldLeft(None: Option[Map[Int, List[BigDecimal]]]) {
            case (None, (i, capacity)) =>
              if (capacity >= head) {
                // if the current resource bin has enough residual capacity,
                // let put the current resource quantity in it and check what happens
                val newResidual = residual.updated(i, capacity - head)
                val newPlaced = placed.updated(i, head :: placed.getOrElse(i, List.empty))
                assignResourceQuantities(tail, newResidual, newPlaced)
              } else {
                None
              }
            case (acc, _) =>
              // we do not look for anything optimal here, and just return
              // the first successful assignment found
              acc
          }
      }
    }

    quantities.length match {
      // if the pod doesn't need any of these resources, just return nothing here
      case 0 => List.empty
      // otherwise let's proceed to bin packing
      case _ =>
        // this maps resource bin indexes to their residual capacities
        val residual = resources.zipWithIndex.map {
          case (r, i) => (i, BigDecimal(r.getScalar.getValue))
        }.toMap
        // this maps resource bin indexes to resource quantities placed into them
        val placedInitial = resources.zipWithIndex.map {
          case (_, i) => (i, List.empty[BigDecimal])
        }.toMap

        // now do search for assignment of resource quantities to available Mesos resources
        assignResourceQuantities(quantities, residual, placedInitial) match {
          case Some(placedQuantities) =>
            val indexedResources = resources.toIndexedSeq
            placedQuantities.toList.flatMap {
              case (i, placed) =>
                val resource = indexedResources(i)
                placed.map { quantity =>
                  val updatedResource =
                    resource.toBuilder.setScalar(mesos.Value.Scalar.newBuilder.setValue(quantity.toDouble)).build
                  (quantity, updatedResource)
                }
            }
          case _ =>
            // Based on how we consume resources in [[ResourceMatcher]],
            // it should be impossible to end up here. If we end up here,
            // either there is a bug in this function, or in ResourceMatcher ones.
            throw new IllegalStateException("failed to assign resource quantities")
        }
    }
  }

  // The resource match provides us with a list of host ports.
  // Each port mapping corresponds to an item in that list.
  // We use that list to swap the dynamic ports (ports set to 0) with the matched ones.
  private[mesos] def buildMesosNetworks(
    networks: Seq[Network],
    endpoints: Seq[raml.Endpoint],
    hostPorts: Seq[Option[Int]],
    mesosBridgeName: String): Seq[mesos.NetworkInfo] = {

    assume(
      endpoints.size == hostPorts.size,
      "expected total number of endpoints to match number of optional host ports")
    val portMappings = endpoints.iterator
      .zip(hostPorts.iterator)
      .collect {
        case (endpoint, Some(hostPort)) =>
          val portMapping = mesos.NetworkInfo.PortMapping.newBuilder
            .setHostPort(hostPort)

          if (endpoint.containerPort.forall(_ == 0)) {
            portMapping.setContainerPort(hostPort)
          } else {
            endpoint.containerPort.foreach(portMapping.setContainerPort)
          }

          // While the protocols in RAML may be declared in a list, Mesos expects a
          // port mapping for every single protocol. If protocols are set, a port mapping
          // will be created for every protocol in the list.
          if (endpoint.protocol.isEmpty) {
            Seq((endpoint.networkNames, portMapping.build))
          } else {
            endpoint.protocol.map { protocol =>
              (endpoint.networkNames, portMapping.setProtocol(protocol).build)
            }
          }
      }
      .flatten
      .toList

    networks.collect{
      case containerNetwork: ContainerNetwork =>
        val b = mesos.NetworkInfo.newBuilder
          .setName(containerNetwork.name)
          .setLabels(containerNetwork.labels.toMesosLabels)
        portMappings.foreach {
          case (networkNames, pm) =>
            // if networkNames is empty, then it means associate with all container networks
            if (networkNames.forall(_ == containerNetwork.name))
              b.addPortMappings(pm)
        }
        b.build
      case bridgeNetwork: BridgeNetwork =>
        val b = mesos.NetworkInfo.newBuilder
          .setName(mesosBridgeName)
          .setLabels(bridgeNetwork.labels.toMesosLabels)
        portMappings.foreach{ case (_, pm) => b.addPortMappings(pm) }
        b.build()
    }
  }

  private[this] def computeTaskInfo(
    container: MesosContainer,
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    instanceId: Instance.Id,
    taskId: Task.Id,
    matchedResources: Resources,
    hostPorts: Seq[Option[Int]],
    config: BuilderConfig,
    portAssignments: Seq[PortAssignment]): mesos.TaskInfo.Builder = {

    val endpointVars = endpointEnvVars(podDefinition, hostPorts, config)

    val builder = mesos.TaskInfo.newBuilder
      .setName(container.name)
      .setTaskId(mesos.TaskID.newBuilder.setValue(taskId.idString))
      .setSlaveId(offer.getSlaveId)

    val consumer = new ResourceConsumer(matchedResources)
    consumer.consumeCpus(container.resources.cpus).foreach(builder.addResources)
    consumer.consumeMem(container.resources.mem).foreach(builder.addResources)
    consumer.consumeDisk(container.resources.disk).foreach(builder.addResources)
    consumer.consumeGpus(container.resources.gpus.toDouble).foreach(builder.addResources)

    if (container.labels.nonEmpty)
      builder.setLabels(mesos.Labels.newBuilder.addAllLabels(container.labels.map {
        case (key, value) =>
          mesos.Label.newBuilder.setKey(key).setValue(value).build
      }.asJava))

    val commandInfo = computeCommandInfo(
      podDefinition,
      instanceId,
      taskId,
      container,
      offer.getHostname,
      endpointVars)

    builder.setCommand(commandInfo)

    computeContainerInfo(podDefinition, container)
      .foreach(builder.setContainer)

    container.healthCheck.foreach { healthCheck =>
      computeHealthCheck(healthCheck, portAssignments).foreach(builder.setHealthCheck)
    }

    for {
      lc <- container.lifecycle
      killGracePeriodSeconds <- lc.killGracePeriodSeconds
    } {
      val durationInfo = DurationInfo.newBuilder.setNanoseconds((killGracePeriodSeconds * SECONDS.toNanos(1)).toLong)
      builder.setKillPolicy(
        KillPolicy.newBuilder.setGracePeriod(durationInfo))
    }

    builder
  }

  private[this] def computeExecutorInfo(
    podDefinition: PodDefinition,
    matchedResources: Resources,
    portsMatch: PortsMatch,
    mesosNetworks: Seq[mesos.NetworkInfo],
    volumeMatchOption: Option[PersistentVolumeMatcher.VolumeMatch],
    instanceId: Instance.Id,
    frameworkId: mesos.FrameworkID): mesos.ExecutorInfo.Builder = {
    val executorID = mesos.ExecutorID.newBuilder.setValue(instanceId.executorIdString)

    val executorInfo = mesos.ExecutorInfo.newBuilder
      .setType(mesos.ExecutorInfo.Type.DEFAULT)
      .setExecutorId(executorID)
      .setFrameworkId(frameworkId)

    val consumer = new ResourceConsumer(matchedResources)
    consumer.consumeCpus(podDefinition.executorResources.cpus).foreach(executorInfo.addResources)
    consumer.consumeMem(podDefinition.executorResources.mem).foreach(executorInfo.addResources)
    consumer.consumeDisk(podDefinition.executorResources.disk).foreach(executorInfo.addResources)
    consumer.consumeGpus(podDefinition.executorResources.gpus.toDouble).foreach(executorInfo.addResources)
    executorInfo.addAllResources(portsMatch.resources.asJava)

    if (podDefinition.networks.nonEmpty || podDefinition.volumes.nonEmpty) {
      val containerInfo = mesos.ContainerInfo.newBuilder
        .setType(mesos.ContainerInfo.Type.MESOS)

      mesosNetworks.foreach(containerInfo.addNetworkInfos)
      volumeMatchOption.foreach(_.persistentVolumeResources.foreach(executorInfo.addResources))
      executorInfo.setContainer(containerInfo)
    }

    executorInfo.setLabels(podDefinition.labels.toMesosLabels)

    executorInfo
  }

  private[this] def computeCommandInfo(
    podDefinition: PodDefinition,
    instanceId: Instance.Id,
    taskId: Task.Id,
    container: MesosContainer,
    host: String,
    portsEnvVars: Map[String, String]): mesos.CommandInfo.Builder = {
    val commandInfo = mesos.CommandInfo.newBuilder

    // By default 'shell' is set to true which will result in an error if the user
    // wants to use Entrypoint/Cmd of Docker images. This is documented in
    // http://mesos.apache.org/documentation/latest/mesos-containerizer/
    // Setting it to false here will allow Entrypoint/Cmd values to work.
    commandInfo.setShell(false)

    container.exec.foreach{ exec =>
      exec.command match {
        case raml.ShellCommand(shell) =>
          commandInfo.setShell(true)
          commandInfo.setValue(shell)
        case raml.ArgvCommand(argv) =>
          commandInfo.setShell(false)
          commandInfo.addAllArguments(argv.asJava)
          if (exec.overrideEntrypoint.getOrElse(false)) {
            argv.headOption.foreach(commandInfo.setValue)
          }
      }
    }

    // Container user overrides pod user
    val user = container.user.orElse(podDefinition.user)
    user.foreach(commandInfo.setUser)

    val uris = container.artifacts.map { artifact =>
      val uri = mesos.CommandInfo.URI.newBuilder
        .setValue(artifact.uri)
        .setCache(artifact.cache)
        .setExtract(artifact.extract)
        .setExecutable(artifact.executable)

      artifact.destPath.foreach(uri.setOutputFile)

      uri.build
    }

    commandInfo.addAllUris(uris.asJava)

    val podEnvVars = podDefinition.env.collect{ case (k: String, v: EnvVarString) => k -> v.value }

    val taskEnvVars = container.env.collect{ case (k: String, v: EnvVarString) => k -> v.value }

    val hostEnvVar = Map("HOST" -> host)

    val taskContextEnvVars = taskContextEnv(container, podDefinition.version, instanceId, taskId)

    val labels = podDefinition.labels ++ container.labels

    val labelEnvVars = EnvironmentHelper.labelsToEnvVars(labels)

    // Variables defined on task level should override ones defined at pod level.
    // Therefore the order here is important. Values for existing keys will be overwritten in the order they are added.
    val envVars = (podEnvVars ++
      taskEnvVars ++
      hostEnvVar ++
      taskContextEnvVars ++
      labelEnvVars ++
      portsEnvVars)
      .map {
        case (name, value) =>
          mesos.Environment.Variable.newBuilder.setName(name).setValue(value).build
      }

    commandInfo.setEnvironment(mesos.Environment.newBuilder.addAllVariables(envVars.asJava))
  }

  private[mesos] def computeContainerInfo(
    podDefinition: PodDefinition,
    container: MesosContainer): Option[mesos.ContainerInfo.Builder] = {

    val containerInfo = mesos.ContainerInfo.newBuilder.setType(mesos.ContainerInfo.Type.MESOS)

    container.volumeMounts.foreach { volumeMount =>

      // Read-write mode will be used when the "readOnly" option isn't set.
      val mode = VolumeMount.readOnlyToProto(volumeMount.readOnly)
      val volumeName = volumeMount.volumeName.getOrElse(
        throw new IllegalArgumentException("volumeName must be not empty"))
      podDefinition.volume(volumeName) match {
        case hostVolume: HostVolume =>
          val volume = mesos.Volume.newBuilder()
            .setMode(mode)
            .setContainerPath(volumeMount.mountPath)
            // TODO(jdef) use source type HOST_PATH once it's available (this will soon be deprecated)
            .setHostPath(hostVolume.hostPath)

          containerInfo.addVolumes(volume)

        case _: EphemeralVolume =>
          val volume = mesos.Volume.newBuilder()
            .setMode(mode)
            .setContainerPath(volumeMount.mountPath)
            .setSource(mesos.Volume.Source.newBuilder()
              .setType(mesos.Volume.Source.Type.SANDBOX_PATH)
              .setSandboxPath(mesos.Volume.Source.SandboxPath.newBuilder()
                .setType(mesos.Volume.Source.SandboxPath.Type.PARENT)
                .setPath(ephemeralVolumePathPrefix + volumeName)
              ))

          containerInfo.addVolumes(volume)

        case _: PersistentVolume =>
          val volume = mesos.Volume.newBuilder()
            .setMode(mode)
            .setContainerPath(volumeMount.mountPath)
            .setSource(mesos.Volume.Source.newBuilder()
              .setType(mesos.Volume.Source.Type.SANDBOX_PATH)
              .setSandboxPath(mesos.Volume.Source.SandboxPath.newBuilder()
                .setType(mesos.Volume.Source.SandboxPath.Type.PARENT)
                .setPath(volumeName)))

          containerInfo.addVolumes(volume)

        case _: SecretVolume => // Is handled in the plugins
      }
    }

    container.image.foreach { im =>
      val image = mesos.Image.newBuilder

      im.forcePull.foreach(forcePull => image.setCached(!forcePull))

      im.kind match {
        case raml.ImageType.Docker =>
          val docker = mesos.Image.Docker.newBuilder.setName(im.id)
          im.pullConfig.foreach { pullConfig =>
            docker.setConfig(SecretSerializer.toSecretReference(pullConfig.secret))
          }
          image.setType(mesos.Image.Type.DOCKER).setDocker(docker)
        case raml.ImageType.Appc =>
          val appcLabels = (LinuxAmd64 ++ im.labels).toMesosLabels
          val appc = mesos.Image.Appc.newBuilder.setName(im.id).setLabels(appcLabels)
          image.setType(mesos.Image.Type.APPC).setAppc(appc)
      }

      val mesosInfo = mesos.ContainerInfo.MesosInfo.newBuilder.setImage(image)
      containerInfo.setMesos(mesosInfo)
    }

    // attach a tty if specified
    container.tty.filter(tty => tty).foreach(containerInfo.setTtyInfo(_))

    // Only create a 'ContainerInfo' when some of it's fields are set.
    // If no fields other than the type have been set, then we shouldn't pass the container info
    if (mesos.ContainerInfo.newBuilder.setType(mesos.ContainerInfo.Type.MESOS).build() == containerInfo.build()) {
      None
    } else {
      Some(containerInfo)
    }
  }

  /**
    * @param podDefinition is queried to determine networking mode
    * @param endpoints are assumed to have had all wildcard ports (e.g. 0) filled in with real values
    */
  private[this] def computePortAssignments(
    podDefinition: PodDefinition,
    endpoints: Seq[Endpoint]): Seq[PortAssignment] = {

    val isHostModeNetworking = podDefinition.networks.contains(HostNetwork)

    endpoints.map { ep =>
      PortAssignment(
        portName = Some(ep.name),
        hostPort = ep.hostPort.find(_ => isHostModeNetworking),
        containerPort = ep.containerPort.find(_ => !isHostModeNetworking),
        // we don't need these for health checks proto generation, presumably because we can't definitively know,
        // in all cases, the full network address of the health check until the task is actually launched.
        effectiveIpAddress = None,
        effectivePort = PortAssignment.NoPort
      )
    }
  }

  private[this] def computeHealthCheck(
    healthCheck: MesosHealthCheck,
    portAssignments: Seq[PortAssignment]): Option[mesos.HealthCheck] = {

    healthCheck match {
      case _: MesosCommandHealthCheck =>
        healthCheck.toMesos()
      case _ =>
        healthCheck.toMesos(portAssignments)
    }
  }

  /**
    * Computes all endpoint env vars for the entire pod definition
    * Form:
    * ENDPOINT_{ENDPOINT_NAME}=123
    */
  private[this] def endpointEnvVars(
    pod: PodDefinition,
    hostPorts: Seq[Option[Int]],
    builderConfig: BuilderConfig): Map[String, String] = {
    val prefix = builderConfig.envVarsPrefix.getOrElse("").toUpperCase
    def escape(name: String): String = name.replaceAll("[^A-Z0-9_]+", "_").toUpperCase

    val hostNetwork = pod.networks.contains(HostNetwork)
    val hostPortByEndpoint = pod.containers.view.flatMap(_.endpoints).zip(hostPorts).toMap.withDefaultValue(None)
    pod.containers.view.flatMap(_.endpoints).flatMap{ endpoint =>
      val mayBePort = if (hostNetwork) hostPortByEndpoint(endpoint) else endpoint.containerPort
      val envName = escape(endpoint.name.toUpperCase)
      Seq(
        mayBePort.map(p => s"${prefix}ENDPOINT_$envName" -> p.toString),
        hostPortByEndpoint(endpoint).map(p => s"${prefix}EP_HOST_$envName" -> p.toString),
        endpoint.containerPort.map(p => s"${prefix}EP_CONTAINER_$envName" -> p.toString)
      ).flatten
    }.toMap
  }

  private[this] def taskContextEnv(
    container: MesosContainer,
    version: Timestamp,
    instanceId: Instance.Id,
    taskId: Task.Id): Map[String, String] = {
    Map(
      "MESOS_TASK_ID" -> Some(taskId.idString),
      "MESOS_EXECUTOR_ID" -> Some(instanceId.executorIdString),
      "MARATHON_APP_ID" -> Some(instanceId.runSpecId.toString),
      "MARATHON_APP_VERSION" -> Some(version.toString),
      "MARATHON_CONTAINER_ID" -> Some(container.name),
      "MARATHON_CONTAINER_RESOURCE_CPUS" -> Some(container.resources.cpus.toString),
      "MARATHON_CONTAINER_RESOURCE_MEM" -> Some(container.resources.mem.toString),
      "MARATHON_CONTAINER_RESOURCE_DISK" -> Some(container.resources.disk.toString),
      "MARATHON_CONTAINER_RESOURCE_GPUS" -> Some(container.resources.gpus.toString)
    ).collect {
        case (key, Some(value)) => key -> value
      }
  }

  private def taskDiscovery(pod: PodDefinition, endpoints: Seq[Endpoint]): mesos.DiscoveryInfo = {
    val ports = PortDiscovery.generateForPod(pod.networks, endpoints)
    mesos.DiscoveryInfo.newBuilder.setPorts(mesos.Ports.newBuilder.addAllPorts(ports.asJava))
      .setName(pod.id.toHostname)
      .setVisibility(org.apache.mesos.Protos.DiscoveryInfo.Visibility.FRAMEWORK)
      .build
  }

  def buildTaskNetworkInfos(
    pod: PodDefinition,
    host: String,
    taskIDs: Seq[Task.Id],
    hostPorts: Seq[Option[Int]]
  ): Map[Task.Id, NetworkInfo] = {

    logger.debug(s"Building network info for tasks $taskIDs with ports $hostPorts")

    val reqPortsByCTName: Seq[(String, Option[Int])] = pod.containers.flatMap { ct =>
      ct.endpoints.map { ep =>
        ct.name -> ep.hostPort
      }
    }

    val totalRequestedPorts = reqPortsByCTName.size
    require(totalRequestedPorts == hostPorts.size, s"expected that number of allocated ports ${hostPorts.size}" +
      s" would equal the number of requested host ports $totalRequestedPorts")

    require(!hostPorts.flatten.contains(0), "expected that all dynamic host ports have been allocated")

    val allocPortsByCTName: Seq[(String, Int)] = reqPortsByCTName.zip(hostPorts).collect {
      case ((name, Some(_)), Some(allocatedPort)) => name -> allocatedPort
    }(collection.breakOut)

    taskIDs.map { taskId =>
      // the task level host ports are needed for fine-grained status/reporting later on
      val taskHostPorts: Seq[Int] = taskId.containerName.map { ctName =>
        allocPortsByCTName.withFilter { case (name, port) => name == ctName }.map(_._2)
      }.getOrElse(Seq.empty[Int])

      val networkInfo = NetworkInfo(host, taskHostPorts, ipAddresses = Nil)
      taskId -> networkInfo
    }(collection.breakOut)
  }
}
