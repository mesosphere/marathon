package mesosphere.marathon
package test

import akka.stream.Materializer
import com.github.fge.jackson.JsonLoader
import com.github.fge.jsonschema.core.report.ProcessingReport
import com.github.fge.jsonschema.main.JsonSchemaFactory
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceChangeHandler
import mesosphere.marathon.core.launcher.impl.{ ReservationLabels, TaskLabels }
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.pod.Network
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceTrackerModule }
import mesosphere.marathon.raml.{ Raml, Resources }
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.PortMapping
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.InstanceRepository
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.{ FrameworkID, OfferID, Range, RangesResource, Resource, ScalarResource, SlaveID }
import mesosphere.mesos.protos.Implicits._
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.Resource.{ DiskInfo, ReservationInfo }
import org.apache.mesos.Protos._
import org.apache.mesos.{ Protos => Mesos }
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext
import scala.util.Random

object MarathonTestHelper {

  lazy val clock = Clock()

  def makeConfig(args: String*): AllConf = {
    new AllConf(args.toIndexedSeq) {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
      verify()
    }
  }

  def defaultConfig(
    maxTasksPerOffer: Int = 1,
    minReviveOffersInterval: Long = 100,
    mesosRole: Option[String] = None,
    acceptedResourceRoles: Option[Set[String]] = None,
    envVarsPrefix: Option[String] = None,
    maxZkNodeSize: Option[Int] = None,
    internalStorageBackend: Option[String] = None): AllConf = {

    var args = Seq(
      "--master", "127.0.0.1:5050",
      "--max_tasks_per_offer", maxTasksPerOffer.toString,
      "--min_revive_offers_interval", minReviveOffersInterval.toString,
      "--mesos_authentication_principal", "marathon"
    )

    mesosRole.foreach(args ++= Seq("--mesos_role", _))
    acceptedResourceRoles.foreach(v => args ++= Seq("--default_accepted_resource_roles", v.mkString(",")))
    maxZkNodeSize.foreach(size => args ++= Seq("--zk_max_node_size", size.toString))
    envVarsPrefix.foreach(args ++= Seq("--env_vars_prefix", _))
    internalStorageBackend.foreach(backend => args ++= Seq("--internal_store_backend", backend))
    makeConfig(args: _*)
  }

  val frameworkID: FrameworkID = FrameworkID("marathon")
  val frameworkId: FrameworkId = FrameworkId("").mergeFromProto(frameworkID)

  def makeBasicOffer(cpus: Double = 4.0, mem: Double = 16000, disk: Double = 1.0,
    beginPort: Int = 31000, endPort: Int = 32000, role: String = ResourceRole.Unreserved,
    reservation: Option[ReservationLabels] = None, gpus: Double = 0.0): Offer.Builder = {

    require(role != ResourceRole.Unreserved || reservation.isEmpty, "reserved resources cannot have role *")

    def heedReserved(resource: Mesos.Resource): Mesos.Resource = {
      reservation match {
        case Some(reservationWithLabels) =>
          val labels = reservationWithLabels.mesosLabels
          val reservation =
            Mesos.Resource.ReservationInfo.newBuilder()
              .setPrincipal("marathon")
              .setLabels(labels)
          resource.toBuilder.setReservation(reservation).build()
        case None =>
          resource
      }
    }

    val cpusResource = heedReserved(ScalarResource(Resource.CPUS, cpus, role = role))
    val gpuResource = heedReserved(ScalarResource(Resource.GPUS, gpus, role = role))
    val memResource = heedReserved(ScalarResource(Resource.MEM, mem, role = role))
    val diskResource = heedReserved(ScalarResource(Resource.DISK, disk, role = role))
    val portsResource = if (beginPort <= endPort) {
      Some(heedReserved(RangesResource(
        Resource.PORTS,
        Seq(Range(beginPort.toLong, endPort.toLong)),
        role
      )))
    } else {
      None
    }
    val offerBuilder = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(frameworkID)
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(gpuResource)
      .addResources(memResource)
      .addResources(diskResource)

    portsResource.foreach(offerBuilder.addResources)

    offerBuilder
  }

  def mountSource(path: Option[String]): Mesos.Resource.DiskInfo.Source = {
    val b = Mesos.Resource.DiskInfo.Source.newBuilder.
      setType(Mesos.Resource.DiskInfo.Source.Type.MOUNT)
    path.foreach { p =>
      b.setMount(Mesos.Resource.DiskInfo.Source.Mount.newBuilder.
        setRoot(p))
    }

    b.build
  }

  def mountSource(path: String): Mesos.Resource.DiskInfo.Source =
    mountSource(Some(path))

  def mountDisk(path: Option[String]): Mesos.Resource.DiskInfo = {
    // val source = Mesos.Resource.DiskInfo.sour
    Mesos.Resource.DiskInfo.newBuilder.
      setSource(
        mountSource(path)).
        build
  }

  def mountDisk(path: String): Mesos.Resource.DiskInfo =
    mountDisk(Some(path))

  def pathSource(path: Option[String]): Mesos.Resource.DiskInfo.Source = {
    val b = Mesos.Resource.DiskInfo.Source.newBuilder.
      setType(Mesos.Resource.DiskInfo.Source.Type.PATH)
    path.foreach { p =>
      b.setPath(Mesos.Resource.DiskInfo.Source.Path.newBuilder.
        setRoot(p))
    }

    b.build
  }

  def pathSource(path: String): Mesos.Resource.DiskInfo.Source =
    pathSource(Some(path))

  def pathDisk(path: Option[String]): Mesos.Resource.DiskInfo = {
    // val source = Mesos.Resource.DiskInfo.sour
    Mesos.Resource.DiskInfo.newBuilder.
      setSource(
        pathSource(path)).
        build
  }

  def pathDisk(path: String): Mesos.Resource.DiskInfo =
    pathDisk(Some(path))

  def scalarResource(
    name: String, d: Double, role: String = ResourceRole.Unreserved,
    reservation: Option[ReservationInfo] = None, disk: Option[DiskInfo] = None): Mesos.Resource = {

    val builder = Mesos.Resource
      .newBuilder()
      .setName(name)
      .setType(Value.Type.SCALAR)
      .setScalar(Value.Scalar.newBuilder().setValue(d))
      .setRole(role)

    reservation.foreach(builder.setReservation)
    disk.foreach(builder.setDisk)

    builder.build()
  }

  def portsResource(
    begin: Long, end: Long, role: String = ResourceRole.Unreserved,
    reservation: Option[ReservationInfo] = None): Mesos.Resource = {

    val ranges = Mesos.Value.Ranges.newBuilder()
      .addRange(Mesos.Value.Range.newBuilder().setBegin(begin).setEnd(end))

    val builder = Mesos.Resource
      .newBuilder()
      .setName(Resource.PORTS)
      .setType(Value.Type.RANGES)
      .setRanges(ranges)
      .setRole(role)

    reservation.foreach(builder.setReservation)

    builder.build()
  }

  def reservation(principal: String, labels: Map[String, String] = Map.empty): Mesos.Resource.ReservationInfo = {
    Mesos.Resource.ReservationInfo.newBuilder()
      .setPrincipal(principal)
      .setLabels(labels.toMesosLabels)
      .build()
  }

  def constraint(field: String, operator: String, value: Option[String]): Constraint = {
    val b = Constraint.newBuilder.
      setField(field).
      setOperator(Operator.valueOf(operator))
    value.foreach(b.setValue)
    b.build
  }

  def reservedDisk(id: String, size: Double = 4096, role: String = ResourceRole.Unreserved,
    principal: String = "test", containerPath: String = "/container"): Mesos.Resource.Builder = {
    Mesos.Resource.newBuilder()
      .setType(Mesos.Value.Type.SCALAR)
      .setName(Resource.DISK)
      .setScalar(Mesos.Value.Scalar.newBuilder.setValue(size))
      .setRole(role)
      .setReservation(ReservationInfo.newBuilder().setPrincipal(principal))
      .setDisk(DiskInfo.newBuilder()
        .setPersistence(DiskInfo.Persistence.newBuilder().setId(id))
        .setVolume(Mesos.Volume.newBuilder()
          .setMode(Mesos.Volume.Mode.RW)
          .setContainerPath(containerPath)
        )
      )
  }

  /**
    * @param ranges how many port ranges should be included in this offer
    * @return
    */
  def makeBasicOfferWithManyPortRanges(ranges: Int): Offer.Builder = {
    val role = ResourceRole.Unreserved
    val cpusResource = ScalarResource(Resource.CPUS, 4.0, role = role)
    val memResource = ScalarResource(Resource.MEM, 16000, role = role)
    val diskResource = ScalarResource(Resource.DISK, 1.0, role = role)
    val portsResource = RangesResource(
      Resource.PORTS,
      List.tabulate(ranges)(_ * 2 + 1).map(p => Range(p.toLong, (p + 1).toLong)),
      role
    )

    val offerBuilder = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(frameworkID)
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addResources(portsResource)

    offerBuilder
  }

  def makeBasicOfferWithRole(cpus: Double, mem: Double, disk: Double,
    beginPort: Int, endPort: Int, role: String) = {
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(Range(beginPort.toLong, endPort.toLong)),
      role
    )
    val cpusResource = ScalarResource(Resource.CPUS, cpus, role)
    val memResource = ScalarResource(Resource.MEM, mem, role)
    val diskResource = ScalarResource(Resource.DISK, disk, role)
    Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(frameworkID)
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addResources(portsResource)
  }

  def makeOneCPUTask(taskId: Task.Id): TaskInfo.Builder = {
    TaskInfo.newBuilder()
      .setName("true")
      .setTaskId(TaskID.newBuilder().setValue(taskId.idString).build())
      .setSlaveId(SlaveID("slave1"))
      .setCommand(CommandInfo.newBuilder().setShell(true).addArguments("true"))
      .addResources(ScalarResource(Resource.CPUS, 1.0, ResourceRole.Unreserved))
  }

  def makeBasicApp() = AppDefinition(
    id = "/test-app".toPath,
    resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
    executor = "//cmd",
    portDefinitions = Seq(PortDefinition(0))
  )

  lazy val appSchema = {
    val appJson = "/public/api/v2/schema/AppDefinition.json"
    val appDefinition = JsonLoader.fromResource(appJson)
    val factory = JsonSchemaFactory.byDefault()
    factory.getJsonSchema(appDefinition)
  }

  def validateJsonSchema(app: AppDefinition, valid: Boolean = true): Unit = {
    // TODO: Revalidate the decision to disallow null values in schema
    // Possible resolution: Do not render null values in our formats by default anymore.
    val appStr = Json.prettyPrint(JsonTestHelper.removeNullFieldValues(Json.toJson(Raml.toRaml(app))))
    validateJsonSchemaForString(appStr, valid)
  }

  // TODO(jdef) re-think validating against this schema; we should be validating against RAML instead
  def validateJsonSchemaForString(appStr: String, valid: Boolean): Unit = {
    val appJson = JsonLoader.fromString(appStr)
    val validationResult: ProcessingReport = appSchema.validate(appJson)
    lazy val pretty = Json.prettyPrint(Json.parse(appStr))
    assert(validationResult.isSuccess == valid, s"validation errors $validationResult for json:\n$pretty")
  }

  def createTaskTrackerModule(
    leadershipModule: LeadershipModule,
    store: Option[InstanceRepository] = None)(implicit mat: Materializer): InstanceTrackerModule = {

    implicit val ctx = ExecutionContext.global
    val instanceRepo = store.getOrElse(InstanceRepository.inMemRepository(new InMemoryPersistenceStore()))
    val updateSteps = Seq.empty[InstanceChangeHandler]

    new InstanceTrackerModule(clock, defaultConfig(), leadershipModule, instanceRepo, updateSteps) {
      // some tests create only one actor system but create multiple task trackers
      override protected lazy val instanceTrackerActorName: String = s"taskTracker_${Random.alphanumeric.take(10).mkString}"
    }
  }

  def emptyInstance(): Instance = Instance(
    instanceId = Task.Id.forRunSpec(PathId("/test")).instanceId,
    agentInfo = Instance.AgentInfo("", None, Nil),
    state = InstanceState(Condition.Created, since = clock.now(), None, healthy = None),
    tasksMap = Map.empty[Task.Id, Task],
    runSpecVersion = clock.now(),
    UnreachableStrategy.default()
  )

  def createTaskTracker(
    leadershipModule: LeadershipModule,
    store: Option[InstanceRepository] = None)(implicit mat: Materializer): InstanceTracker = {
    createTaskTrackerModule(leadershipModule, store).instanceTracker
  }

  def persistentVolumeResources(taskId: Task.Id, localVolumeIds: Task.LocalVolumeId*) = localVolumeIds.map { id =>
    Mesos.Resource.newBuilder()
      .setName("disk")
      .setType(Mesos.Value.Type.SCALAR)
      .setScalar(Mesos.Value.Scalar.newBuilder().setValue(10))
      .setRole("test")
      .setReservation(
        Mesos.Resource.ReservationInfo
          .newBuilder()
          .setPrincipal("principal")
          .setLabels(TaskLabels.labelsForTask(frameworkId, taskId).mesosLabels)
      )
      .setDisk(Mesos.Resource.DiskInfo.newBuilder()
        .setPersistence(Mesos.Resource.DiskInfo.Persistence.newBuilder().setId(id.idString))
        .setVolume(Mesos.Volume.newBuilder()
          .setContainerPath(id.containerPath)
          .setMode(Mesos.Volume.Mode.RW)))
      .build()
  }

  def offerWithVolumes(taskId: Task.Id, localVolumeIds: Task.LocalVolumeId*) = {
    MarathonTestHelper.makeBasicOffer(
      reservation = Some(TaskLabels.labelsForTask(frameworkId, taskId)),
      role = "test"
    ).addAllResources(persistentVolumeResources(taskId, localVolumeIds: _*)).build()
  }

  def offerWithVolumesOnly(taskId: Task.Id, localVolumeIds: Task.LocalVolumeId*) = {
    MarathonTestHelper.makeBasicOffer()
      .clearResources()
      .addAllResources(persistentVolumeResources(taskId, localVolumeIds: _*))
      .build()
  }

  def appWithPersistentVolume(): AppDefinition = {
    MarathonTestHelper.makeBasicApp().copy(
      container = Some(mesosContainerWithPersistentVolume),
      residency = Some(Residency(
        Residency.defaultRelaunchEscalationTimeoutSeconds,
        Residency.defaultTaskLostBehaviour))
    )
  }

  def mesosContainerWithPersistentVolume = Container.Mesos(
    volumes = Seq[mesosphere.marathon.state.Volume](
      PersistentVolume(
        containerPath = "persistent-volume",
        persistent = PersistentVolumeInfo(10), // must match persistentVolumeResources
        mode = Mesos.Volume.Mode.RW
      )
    )
  )

  def mesosIpAddress(ipAddress: String) = {
    Mesos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ipAddress).build
  }

  def networkInfoWithIPAddress(ipAddress: Mesos.NetworkInfo.IPAddress) = {
    Mesos.NetworkInfo.newBuilder().addIpAddresses(ipAddress).build
  }

  def containerStatusWithNetworkInfo(networkInfo: Mesos.NetworkInfo) = {
    Mesos.ContainerStatus.newBuilder().addNetworkInfos(networkInfo).build
  }

  object Implicits {
    implicit class AppDefinitionImprovements(app: AppDefinition) {
      def withPortDefinitions(portDefinitions: Seq[PortDefinition]): AppDefinition =
        app.copy(portDefinitions = portDefinitions)

      def withNoPortDefinitions(): AppDefinition = app.withPortDefinitions(Seq.empty)

      def withDockerNetworks(networks: Network*): AppDefinition = {
        val docker = app.container.getOrElse(Container.Mesos()) match {
          case docker: Docker => docker
          case _ => Docker(image = "busybox")
        }

        app.copy(container = Some(docker), networks = networks.to[Seq])
      }

      def withPortMappings(newPortMappings: Seq[PortMapping]): AppDefinition = {
        val container = app.container.getOrElse(Container.Mesos())
        val docker = container.docker.getOrElse(Docker(image = "busybox")).copy(portMappings = newPortMappings)

        app.copy(container = Some(docker))
      }

      def withHealthCheck(healthCheck: mesosphere.marathon.core.health.HealthCheck): AppDefinition =
        app.copy(healthChecks = Set(healthCheck))
    }

    implicit class TaskImprovements(task: Task) {
      def withNetworkInfo(networkInfo: core.task.state.NetworkInfo): Task = {
        val newStatus = task.status.copy(networkInfo = networkInfo)
        task match {
          case launchedEphemeral: Task.LaunchedEphemeral => launchedEphemeral.copy(status = newStatus)
          case launchedOnReservation: Task.LaunchedOnReservation => launchedOnReservation.copy(status = newStatus)
          case reserved: Task.Reserved => reserved.copy(status = newStatus)
        }
      }

      def withNetworkInfo(hostName: Option[String] = None, hostPorts: Seq[Int] = Nil, networkInfos: scala.collection.Seq[NetworkInfo] = Nil): Task = {
        def containerStatus(networkInfos: scala.collection.Seq[NetworkInfo]) = {
          Mesos.ContainerStatus.newBuilder().addAllNetworkInfos(networkInfos)
        }
        def mesosStatus(taskId: Task.Id, mesosStatus: Option[TaskStatus], networkInfos: scala.collection.Seq[NetworkInfo]): Option[TaskStatus] = {
          val taskState = mesosStatus.fold(TaskState.TASK_STAGING)(_.getState)
          Some(mesosStatus.getOrElse(Mesos.TaskStatus.getDefaultInstance).toBuilder
            .setContainerStatus(containerStatus(networkInfos))
            .setState(taskState)
            .setTaskId(taskId.mesosTaskId)
            .build)
        }
        val taskStatus = mesosStatus(task.taskId, task.status.mesosStatus, networkInfos)
        val ipAddresses: Seq[Mesos.NetworkInfo.IPAddress] = networkInfos.flatMap(_.getIpAddressesList)(collection.breakOut)
        val initialNetworkInfo = core.task.state.NetworkInfo(
          hostName.getOrElse("host.some"),
          hostPorts = hostPorts,
          ipAddresses = ipAddresses)
        val networkInfo = taskStatus.fold(initialNetworkInfo)(initialNetworkInfo.update)
        withNetworkInfo(networkInfo).withStatus(_.copy(mesosStatus = taskStatus))
      }

      def withStatus[T <: Task](update: Task.Status => Task.Status): T = task match {
        case launchedEphemeral: Task.LaunchedEphemeral =>
          launchedEphemeral.copy(status = update(launchedEphemeral.status)).asInstanceOf[T]

        case launchedOnReservation: Task.LaunchedOnReservation =>
          launchedOnReservation.copy(status = update(launchedOnReservation.status)).asInstanceOf[T]

        case reserved: Task.Reserved =>
          throw new scala.RuntimeException("Reserved task cannot have a status")
      }

    }
  }

}
