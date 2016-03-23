package mesosphere.marathon.integration.setup

import java.io.File

import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.integration.facades.{ MesosFacade, ITEnrichedTask, ITDeploymentResult, MarathonFacade }
import mesosphere.marathon.state.{ DockerVolume, AppDefinition, Container, PathId }
import org.apache.commons.io.FileUtils
import org.apache.mesos.Protos
import org.apache.zookeeper.{ WatchedEvent, Watcher, ZooKeeper }
import org.scalatest.{ BeforeAndAfterAllConfigMap, ConfigMap, Suite }
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.util.Try

// scalastyle:off magic.number
object SingleMarathonIntegrationTest {
  private val log = LoggerFactory.getLogger(getClass)
}

/**
  * Convenient trait to test against one marathon instance.
  * Following things are managed at start:
  * (-) a marathon instance is launched.
  * (-) all existing groups, apps and event listeners are removed.
  * (-) a local http server is launched.
  * (-) a callback event handler is registered.
  * (-) this test suite is registered as callback event listener. every event is stored in a queue.
  * (-) a marathonFacade is provided
  *
  * After the test is finished, everything will be clean up.
  */
trait SingleMarathonIntegrationTest
    extends ExternalMarathonIntegrationTest
    with BeforeAndAfterAllConfigMap with MarathonCallbackTestSupport { self: Suite =>

  import SingleMarathonIntegrationTest.log

  /**
    * We only want to fail for configuration problems if the configuration is actually used.
    */
  private var configOption: Option[IntegrationTestConfig] = None
  def config: IntegrationTestConfig = configOption.get

  lazy val appMock: AppMockFacade = new AppMockFacade()

  val testBasePath: PathId = PathId("/marathonintegrationtest")
  override lazy val marathon: MarathonFacade = new MarathonFacade(config.marathonUrl, testBasePath)
  lazy val mesos: MesosFacade = new MesosFacade(s"http://${config.master}")

  protected def extraMarathonParameters: List[String] = List.empty[String]
  protected def marathonParameters: List[String] = List(
    "--master", config.master,
    "--event_subscriber", "http_callback",
    "--access_control_allow_origin", "*",
    "--reconciliation_initial_delay", "600000",
    "--min_revive_offers_interval", "100"
  ) ++ extraMarathonParameters

  lazy val marathonProxy = {
    startMarathon(config.marathonBasePort + 1, "--master", config.master, "--event_subscriber", "http_callback")
    new MarathonFacade(config.copy(marathonBasePort = config.marathonBasePort + 1).marathonUrl, testBasePath)
  }

  implicit class PathIdTestHelper(path: String) {
    def toRootTestPath: PathId = testBasePath.append(path).canonicalPath()
    def toTestPath: PathId = testBasePath.append(path)
  }

  protected def startZooKeeperProcess(port: Int = config.zkPort,
                                      path: String = "/tmp/foo/single",
                                      wipeWorkDir: Boolean = true): Unit = {
    ProcessKeeper.startZooKeeper(port, path, wipeWorkDir)
  }

  override protected def beforeAll(configMap: ConfigMap): Unit = {
    log.info("Setting up local mesos/marathon infrastructure...")
    configOption = Some(IntegrationTestConfig(configMap))
    super.beforeAll(configMap)

    if (!config.useExternalSetup) {
      //make sure last test cleared everything
      ProcessKeeper.shutdown()

      log.info("Setting up local mesos/marathon infrastructure...")
      startZooKeeperProcess()
      ProcessKeeper.startMesosLocal()
      cleanMarathonState()

      startMarathon(config.marathonBasePort, marathonParameters: _*)

      waitForCleanSlateInMesos()
      log.info("Setting up local mesos/marathon infrastructure: done.")
    }
    else {
      log.info("Using already running Marathon at {}", config.marathonUrl)
    }

    startCallbackEndpoint(config.httpPort, config.cwd)
  }

  override protected def afterAll(configMap: ConfigMap): Unit = {
    super.afterAll(configMap)
    cleanUp(withSubscribers = !config.useExternalSetup)

    log.info("Cleaning up local mesos/marathon structure...")
    ExternalMarathonIntegrationTest.healthChecks.clear()
    ProcessKeeper.shutdown()
    ProcessKeeper.stopJavaProcesses("mesosphere.marathon.integration.setup.AppMock")
    system.shutdown()
    system.awaitTermination()
    log.info("Cleaning up local mesos/marathon structure: done.")
  }

  def cleanMarathonState() {
    val watcher = new Watcher { override def process(event: WatchedEvent): Unit = println(event) }
    val zooKeeper = new ZooKeeper(config.zkHostAndPort, 30 * 1000, watcher)
    def deletePath(path: String) {
      if (zooKeeper.exists(path, false) != null) {
        val children = zooKeeper.getChildren(path, false)
        children.asScala.foreach(sub => deletePath(s"$path/$sub"))
        zooKeeper.delete(path, -1)
      }
    }
    deletePath(config.zkPath)
    zooKeeper.close()
  }

  def waitForTasks(appId: PathId, num: Int, maxWait: FiniteDuration = 30.seconds): List[ITEnrichedTask] = {
    def checkTasks: Option[List[ITEnrichedTask]] = {
      val tasks = Try(marathon.tasks(appId)).map(_.value).getOrElse(Nil).filter(_.launched)
      if (tasks.size == num) Some(tasks) else None
    }
    WaitTestSupport.waitFor(s"$num tasks to launch", maxWait)(checkTasks)
  }

  def waitForHealthCheck(check: IntegrationHealthCheck, maxWait: FiniteDuration = 30.seconds) = {
    WaitTestSupport.waitUntil("Health check to get queried", maxWait) { check.pinged }
  }

  private def appProxyMainInvocationImpl: String = {
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes").replaceAll(" ", "")
    val main = classOf[AppMock].getName
    s"""$javaExecutable -Xmx64m -classpath $classPath $main"""
  }

  /**
    * Writes the appProxy invocation command into a shell script -- otherwise the whole log
    * of the test is spammed by overly long classpath definitions.
    */
  private lazy val appProxyMainInvocation: String = {
    val file = File.createTempFile("appProxy", ".sh")
    file.deleteOnExit()

    FileUtils.write(file,
      s"""#!/bin/sh
          |set -x
          |exec $appProxyMainInvocationImpl $$*""".stripMargin)
    file.setExecutable(true)

    file.getAbsolutePath
  }

  private lazy val appProxyHealthChecks = Set(
    HealthCheck(gracePeriod = 20.second, interval = 1.second, maxConsecutiveFailures = 10))

  def dockerAppProxy(appId: PathId, versionId: String, instances: Int, withHealth: Boolean = true, dependencies: Set[PathId] = Set.empty): AppDefinition = {
    val targetDirs = sys.env.getOrElse("TARGET_DIRS", "/marathon")
    val cmd = Some(s"""bash -c 'echo APP PROXY $$MESOS_TASK_ID RUNNING; $appProxyMainInvocationImpl $appId $versionId http://$$HOST:${config.httpPort}/health$appId/$versionId'""")
    AppDefinition(
      id = appId,
      cmd = cmd,
      container = Some(
        new Container(
          docker = Some(new mesosphere.marathon.state.Container.Docker(
            image = s"""marathon-buildbase:${sys.env.getOrElse("BUILD_ID", "test")}""",
            network = Some(Protos.ContainerInfo.DockerInfo.Network.HOST)
          )),
          volumes = collection.immutable.Seq(
            new DockerVolume(hostPath = env.getOrElse("IVY2_DIR", "/root/.ivy2"), containerPath = "/root/.ivy2", mode = Protos.Volume.Mode.RO),
            new DockerVolume(hostPath = env.getOrElse("SBT_DIR", "/root/.sbt"), containerPath = "/root/.sbt", mode = Protos.Volume.Mode.RO),
            new DockerVolume(hostPath = env.getOrElse("SBT_DIR", "/root/.sbt"), containerPath = "/root/.sbt", mode = Protos.Volume.Mode.RO),
            new DockerVolume(hostPath = s"""$targetDirs/main""", containerPath = "/marathon/target", mode = Protos.Volume.Mode.RO),
            new DockerVolume(hostPath = s"""$targetDirs/project""", containerPath = "/marathon/project/target", mode = Protos.Volume.Mode.RO)
          )
        )
      ),
      instances = instances,
      cpus = 0.5,
      mem = 128.0,
      healthChecks = if (withHealth) appProxyHealthChecks else Set.empty[HealthCheck],
      dependencies = dependencies
    )
  }

  def appProxy(appId: PathId, versionId: String, instances: Int, withHealth: Boolean = true, dependencies: Set[PathId] = Set.empty): AppDefinition = {
    val cmd = Some(s"""echo APP PROXY $$MESOS_TASK_ID RUNNING; $appProxyMainInvocation $appId $versionId http://localhost:${config.httpPort}/health$appId/$versionId""")
    AppDefinition(
      id = appId,
      cmd = cmd,
      executor = "//cmd",
      instances = instances,
      cpus = 0.5,
      mem = 128.0,
      healthChecks = if (withHealth) appProxyHealthChecks else Set.empty[HealthCheck],
      dependencies = dependencies
    )
  }

  def appProxyCheck(appId: PathId, versionId: String, state: Boolean): IntegrationHealthCheck = {
    //this is used for all instances, as long as there is no specific instance check
    //the specific instance check has also a specific port, which is assigned by mesos
    val check = new IntegrationHealthCheck(appId, versionId, 0, state)
    ExternalMarathonIntegrationTest.healthChecks
      .filter(c => c.appId == appId && c.versionId == versionId)
      .foreach(ExternalMarathonIntegrationTest.healthChecks -= _)
    ExternalMarathonIntegrationTest.healthChecks += check
    check
  }

  def taskProxyChecks(appId: PathId, versionId: String, state: Boolean): Seq[IntegrationHealthCheck] = {
    marathon.tasks(appId).value.flatMap(_.ports).flatMap(_.map { port =>
      val check = new IntegrationHealthCheck(appId, versionId, port, state)
      ExternalMarathonIntegrationTest.healthChecks
        .filter(c => c.appId == appId && c.versionId == versionId)
        .foreach(ExternalMarathonIntegrationTest.healthChecks -= _)
      ExternalMarathonIntegrationTest.healthChecks += check
      check
    })
  }

  def cleanUp(withSubscribers: Boolean = false, maxWait: FiniteDuration = 30.seconds) {
    log.info("Starting to CLEAN UP !!!!!!!!!!")
    events.clear()
    ExternalMarathonIntegrationTest.healthChecks.clear()

    val deleteResult: RestResult[ITDeploymentResult] = marathon.deleteGroup(testBasePath, force = true)
    if (deleteResult.code != 404) {
      waitForChange(deleteResult)
    }

    waitForCleanSlateInMesos()

    val apps = marathon.listAppsInBaseGroup
    require(apps.value.isEmpty, s"apps weren't empty: ${apps.entityPrettyJsonString}")
    val groups = marathon.listGroupsInBaseGroup
    require(groups.value.isEmpty, s"groups weren't empty: ${groups.entityPrettyJsonString}")
    ProcessKeeper.stopJavaProcesses("mesosphere.marathon.integration.setup.AppMock")

    if (withSubscribers) marathon.listSubscribers.value.urls.foreach(marathon.unsubscribe)
    events.clear()
    ExternalMarathonIntegrationTest.healthChecks.clear()
    log.info("CLEAN UP finished !!!!!!!!!")
  }

  def waitForCleanSlateInMesos(): Boolean = {
    require(mesos.state.value.agents.size == 1, "one agent expected")
    WaitTestSupport.waitUntil("clean slate in Mesos", 30.seconds) {
      val agent = mesos.state.value.agents.head
      val empty = agent.usedResources.isEmpty && agent.reservedResourcesByRole.isEmpty
      if (!empty) {
        import mesosphere.marathon.integration.facades.MesosFormats._
        log.info(
          "Waiting for blank slate Mesos...\n \"used_resources\": "
            + Json.prettyPrint(Json.toJson(agent.usedResources)) + "\n \"reserved_resources\": "
            + Json.prettyPrint(Json.toJson(agent.reservedResourcesByRole))
        )
      }
      empty
    }
  }
}
