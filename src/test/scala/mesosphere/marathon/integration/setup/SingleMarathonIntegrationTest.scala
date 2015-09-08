package mesosphere.marathon.integration.setup

import java.io.File

import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.{ AppDefinition, PathId }
import org.apache.commons.io.FileUtils
import org.apache.zookeeper.{ WatchedEvent, Watcher, ZooKeeper }
import org.scalatest.{ BeforeAndAfterAllConfigMap, ConfigMap, Suite }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.util.Try

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

  def extraMarathonParameters: List[String] = List.empty[String]

  lazy val marathonProxy = {
    startMarathon(config.marathonBasePort + 1, "--master", config.master, "--event_subscriber", "http_callback")
    new MarathonFacade(config.copy(marathonBasePort = config.marathonBasePort + 1).marathonUrl, testBasePath)
  }

  implicit class PathIdTestHelper(path: String) {
    def toRootTestPath: PathId = testBasePath.append(path).canonicalPath()
    def toTestPath: PathId = testBasePath.append(path)
  }

  override protected def beforeAll(configMap: ConfigMap): Unit = {
    log.info("Setting up local mesos/marathon infrastructure...")
    configOption = Some(IntegrationTestConfig(configMap))
    super.beforeAll(configMap)

    if (!config.useExternalSetup) {
      //make sure last test cleared everything
      ProcessKeeper.shutdown()

      log.info("Setting up local mesos/marathon infrastructure...")
      ProcessKeeper.startZooKeeper(config.zkPort, "/tmp/foo/single")
      ProcessKeeper.startMesosLocal()
      cleanMarathonState()

      val parameters = List(
        "--master", config.master,
        "--event_subscriber", "http_callback",
        "--access_control_allow_origin", "*",
        "--min_revive_offers_interval", "100"
      ) ++ extraMarathonParameters
      startMarathon(config.marathonBasePort, parameters: _*)

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
      val tasks = Try(marathon.tasks(appId)).map(_.value).getOrElse(Nil)
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

  def v2AppProxy(appId: PathId, versionId: String, instances: Int, withHealth: Boolean = true, dependencies: Set[PathId] = Set.empty): V2AppDefinition =
    V2AppDefinition(appProxy(appId, versionId, instances, withHealth, dependencies))

  private[this] def appProxy(appId: PathId, versionId: String, instances: Int, withHealth: Boolean, dependencies: Set[PathId]): AppDefinition = {
    val mainInvocation = appProxyMainInvocation
    val exec = Some(s"""echo APP PROXY $$MESOS_TASK_ID RUNNING; $mainInvocation $appId $versionId http://localhost:${config.httpPort}/health$appId/$versionId""")
    val health = if (withHealth) Set(HealthCheck(gracePeriod = 20.second, interval = 1.second, maxConsecutiveFailures = 10)) else Set.empty[HealthCheck]
    AppDefinition(appId, exec, executor = "//cmd", instances = instances, cpus = 0.5, mem = 128.0, healthChecks = health, dependencies = dependencies)
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
    marathon.tasks(appId).value.flatMap(_.ports).map { port =>
      val check = new IntegrationHealthCheck(appId, versionId, port, state)
      ExternalMarathonIntegrationTest.healthChecks
        .filter(c => c.appId == appId && c.versionId == versionId)
        .foreach(ExternalMarathonIntegrationTest.healthChecks -= _)
      ExternalMarathonIntegrationTest.healthChecks += check
      check
    }
  }

  def cleanUp(withSubscribers: Boolean = false, maxWait: FiniteDuration = 30.seconds) {
    log.info("Starting to CLEAN UP !!!!!!!!!!")
    events.clear()
    ExternalMarathonIntegrationTest.healthChecks.clear()

    val deleteResult: RestResult[ITDeploymentResult] = marathon.deleteGroup(testBasePath, force = true)
    if (deleteResult.code != 404) {
      waitForChange(deleteResult)
    }

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
}
