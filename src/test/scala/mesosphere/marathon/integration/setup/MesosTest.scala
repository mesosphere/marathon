package mesosphere.marathon
package integration.setup

import java.io.File
import java.nio.file.Files

import akka.Done
import akka.actor.{ ActorSystem, Scheduler }
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.stream.Materializer
import mesosphere.marathon.integration.facades.MesosFacade
import mesosphere.marathon.util.Retry
import mesosphere.util.PortAllocator
import org.apache.commons.io.FileUtils
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Suite }

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.sys.process.Process
import scala.util.Try
import scala.async.Async._

case class MesosConfig(
  launcher: String = "posix",
  containerizers: String = "mesos",
  isolation: Option[String] = None,
  imageProviders: Option[String] = None)

/**
  * Runs a mesos-local on a ephemeral port
  *
  * close() should be called when the server is no longer necessary
  */
case class MesosLocal(
    suiteName: String,
    numSlaves: Int = 1,
    autoStart: Boolean = true,
    config: MesosConfig = MesosConfig(),
    waitForStart: FiniteDuration = 30.seconds)(implicit
  system: ActorSystem,
    mat: Materializer,
    ctx: ExecutionContext,
    scheduler: Scheduler) extends AutoCloseable {
  lazy val port = PortAllocator.ephemeralPort()
  lazy val masterUrl = s"127.0.0.1:$port"

  private def write(dir: File, fileName: String, content: String): String = {
    val file = File.createTempFile(fileName, "", dir)
    file.deleteOnExit()
    FileUtils.write(file, content)
    file.setReadable(true)
    file.getAbsolutePath
  }

  private lazy val mesosWorkDir = {
    val tmp = Files.createTempDirectory("mesos-local").toFile
    tmp.deleteOnExit()
    tmp
  }

  private lazy val mesosEnv = {
    val credentialsPath = write(mesosWorkDir, fileName = "credentials", content = "principal1 secret1")
    val aclsPath = write(mesosWorkDir, fileName = "acls.json", content =
      """
        |{
        |  "run_tasks": [{
        |    "principals": { "type": "ANY" },
        |    "users": { "type": "ANY" }
        |  }],
        |  "register_frameworks": [{
        |    "principals": { "type": "ANY" },
        |    "roles": { "type": "ANY" }
        |  }],
        |  "reserve_resources": [{
        |    "roles": { "type": "ANY" },
        |    "principals": { "type": "ANY" },
        |    "resources": { "type": "ANY" }
        |  }],
        |  "create_volumes": [{
        |    "roles": { "type": "ANY" },
        |    "principals": { "type": "ANY" },
        |    "volume_types": { "type": "ANY" }
        |  }]
        |}
      """.stripMargin)

    Seq(
      "MESOS_WORK_DIR" -> mesosWorkDir.getAbsolutePath,
      "MESOS_RUNTIME_DIR" -> new File(mesosWorkDir, "runtime").getAbsolutePath,
      "MESOS_LAUNCHER" -> "posix",
      "MESOS_CONTAINERIZERS" -> config.containerizers,
      "MESOS_LAUNCHER" -> config.launcher,
      "MESOS_ROLES" -> "public,foo",
      "MESOS_ACLS" -> s"file://$aclsPath",
      "MESOS_CREDENTIALS" -> s"file://$credentialsPath",
      "MESOS_SYSTEMD_ENABLE_SUPPORT" -> "false",
      "MESOS_SWITCH_USER" -> "false") ++
      config.isolation.map("MESOS_ISOLATION" -> _).to[Seq] ++
      config.imageProviders.map("MESOS_IMAGE_PROVIDERS" -> _).to[Seq]
  }

  private def create(): Process = {
    val process = Process(
      s"mesos-local --ip=127.0.0.1 --port=$port --work_dir=${mesosWorkDir.getAbsolutePath}",
      cwd = None, mesosEnv: _*)
    process.run(ProcessOutputToLogStream(s"$suiteName-MesosLocal-$port"))
  }

  private var mesosLocal = Option.empty[Process]

  if (autoStart) {
    start()
  }

  def start(): Future[Done] = {
    if (mesosLocal.isEmpty) {
      mesosLocal = Some(create())
    }
    Retry(s"mesos-local-$port", Int.MaxValue, maxDelay = waitForStart) {
      Http(system).singleRequest(Get(s"http://localhost:$port/version")).map { result =>
        if (result.status.isSuccess()) {
          Done
        } else {
          throw new Exception(s"Mesos-local-$port not available")
        }
      }
    }
  }

  def stop(): Unit = {
    mesosLocal.foreach { process =>
      process.destroy()
    }
    mesosLocal = Option.empty[Process]
  }

  def clean(): Unit = {
    val client = new MesosFacade(masterUrl)
    while (client.state.value.agents.exists(agent => !agent.usedResources.isEmpty || agent.reservedResourcesByRole.nonEmpty)) {
      client.frameworkIds().value.foreach(client.terminate)
    }
  }

  override def close(): Unit = {
    Try(clean())
    Try(stop())
    Try(FileUtils.deleteDirectory(mesosWorkDir))
  }
}

case class MesosCluster(
    suiteName: String,
    numMasters: Int,
    numSlaves: Int,
    masterUrl: String,
    quorumSize: Int = 1,
    autoStart: Boolean = false,
    config: MesosConfig = MesosConfig(),
    waitForLeaderTimeout: FiniteDuration = 30.seconds)(implicit
  system: ActorSystem,
    mat: Materializer,
    ctx: ExecutionContext,
    scheduler: Scheduler) extends AutoCloseable {
  require(quorumSize > 0 && quorumSize <= numMasters)

  lazy val masters = 0.until(numMasters).map { i =>
    Mesos(master = true, Seq(
      "--slave_ping_timeout=1secs",
      "--max_slave_ping_timeouts=4",
      s"--quorum=$quorumSize"))
  }

  lazy val agents = 0.until(numSlaves).map { i =>
    Mesos(master = false, Seq(s"--hostname=$i"))
  }

  if (autoStart) {
    start()
  }

  def start(): Future[String] = {
    masters.foreach(_.start())
    agents.foreach(_.start())
    waitForLeader()
  }

  def waitForLeader(): Future[String] = async {
    val result = Retry("wait for leader", maxAttempts = Int.MaxValue, maxDelay = waitForLeaderTimeout) {
      Http().singleRequest(Get(s"http://localhost:${masters.head.port}/redirect")).map { result =>
        if (result.status.isFailure()) {
          throw new Exception(s"Couldn't determine leader: $result")
        }
        result
      }
    }

    await(result).headers.find(_.lowercaseName() == "location").map(_.value()).get
  }

  def stop(): Unit = {
    masters.foreach(_.stop())
    agents.foreach(_.stop())
  }

  private def defaultContainerizers: String = {
    if (sys.env.getOrElse("RUN_DOCKER_INTEGRATION_TESTS", "false") == "true") {
      "docker,mesos"
    } else {
      "mesos"
    }
  }

  private def mesosEnv(mesosWorkDir: File): Seq[(String, String)] = {
    def write(dir: File, fileName: String, content: String): String = {
      val file = File.createTempFile(fileName, "", dir)
      file.deleteOnExit()
      FileUtils.write(file, content)
      file.setReadable(true)
      file.getAbsolutePath
    }

    val credentialsPath = write(mesosWorkDir, fileName = "credentials", content = "principal1 secret1")
    val aclsPath = write(mesosWorkDir, fileName = "acls.json", content =
      """
        |{
        |  "run_tasks": [{
        |    "principals": { "type": "ANY" },
        |    "users": { "type": "ANY" }
        |  }],
        |  "register_frameworks": [{
        |    "principals": { "type": "ANY" },
        |    "roles": { "type": "ANY" }
        |  }],
        |  "reserve_resources": [{
        |    "roles": { "type": "ANY" },
        |    "principals": { "type": "ANY" },
        |    "resources": { "type": "ANY" }
        |  }],
        |  "create_volumes": [{
        |    "roles": { "type": "ANY" },
        |    "principals": { "type": "ANY" },
        |    "volume_types": { "type": "ANY" }
        |  }]
        |}
      """.stripMargin)
    Seq(
      "MESOS_WORK_DIR" -> mesosWorkDir.getAbsolutePath,
      "MESOS_RUNTIME_DIR" -> new File(mesosWorkDir, "runtime").getAbsolutePath,
      "MESOS_LAUNCHER" -> "posix",
      "MESOS_CONTAINERIZERS" -> config.containerizers,
      "MESOS_LAUNCHER" -> config.launcher,
      "MESOS_ROLES" -> "public,foo",
      "MESOS_ACLS" -> s"file://$aclsPath",
      "MESOS_CREDENTIALS" -> s"file://$credentialsPath",
      "MESOS_SYSTEMD_ENABLE_SUPPORT" -> "false",
      "MESOS_SWITCH_USER" -> "false") ++
      config.isolation.map("MESOS_ISOLATION" -> _).to[Seq] ++
      config.imageProviders.map("MESOS_IMAGE_PROVIDERS" -> _).to[Seq]
  }

  case class Mesos(master: Boolean, extraArgs: Seq[String]) extends AutoCloseable {
    val port = PortAllocator.ephemeralPort()
    private val workDir = Files.createTempDirectory(s"mesos-master$port").toFile
    private val processBuilder = Process(
      command = Seq(
      "mesos",
      if (master) "master" else "slave",
      "--ip=127.0.0.1",
      s"--port=$port",
      if (master) s"--zk=$masterUrl" else s"--master=$masterUrl",
      s"--work_dir=${workDir.getAbsolutePath}") ++ extraArgs,
      cwd = None, extraEnv = mesosEnv(workDir): _*)
    private var process = Option.empty[Process]

    if (autoStart) {
      start()
    }

    def start(): Unit = if (process.isEmpty) {
      process = Some(create())
    }

    def stop(): Unit = {
      process.foreach(_.destroy())
      process = Option.empty[Process]
    }

    private def create(): Process = {
      val name = if (master) "Master" else "Agent"
      processBuilder.run(ProcessOutputToLogStream(s"$suiteName-Mesos$name-$port"))
    }

    override def close(): Unit = {
      stop()
      Try(FileUtils.deleteDirectory(workDir))
    }
  }

  def clean(): Unit = {
    val client = new MesosFacade(Await.result(waitForLeader(), waitForLeaderTimeout))
    while (client.state.value.agents.exists(agent => !agent.usedResources.isEmpty || agent.reservedResourcesByRole.nonEmpty)) {
      client.frameworkIds().value.foreach(client.terminate)
    }
  }

  override def close(): Unit = {
    Try(clean())
    agents.foreach(_.close())
    masters.foreach(_.close())
  }
}

trait MesosTest {
  def mesos: MesosFacade
  val mesosMasterUrl: String
  def cleanMesos(): Unit
}

trait SimulatedMesosTest extends MesosTest {
  def mesos: MesosFacade = {
    require(false, "No access to mesos")
    ???
  }
  def cleanMesos(): Unit = {}
  val mesosMasterUrl = ""
}

/**
  * Warning: mesos-local isn't super stable, prefer [[MesosClusterTest]]
  */
trait MesosLocalTest extends Suite with ScalaFutures with MesosTest with BeforeAndAfterAll {
  implicit val system: ActorSystem
  implicit val mat: Materializer
  implicit val ctx: ExecutionContext
  implicit val scheduler: Scheduler
  lazy val mesosConfig = MesosConfig()

  val mesosNumSlaves = 1

  lazy val mesosLocalServer = MesosLocal(
    suiteName = suiteName,
    autoStart = false,
    waitForStart = patienceConfig.timeout.toMillis.milliseconds,
    config = mesosConfig,
    numSlaves = mesosNumSlaves)
  lazy val port = mesosLocalServer.port
  lazy val mesosMasterUrl = mesosLocalServer.masterUrl
  lazy val mesos = new MesosFacade(s"http://$mesosMasterUrl")

  override def cleanMesos(): Unit = mesosLocalServer.clean()

  abstract override def beforeAll(): Unit = {
    super.beforeAll()
    mesosLocalServer.start().futureValue
  }

  abstract override def afterAll(): Unit = {
    mesosLocalServer.close()
    super.afterAll()
  }
}

trait MesosClusterTest extends Suite with ZookeeperServerTest with MesosTest with ScalaFutures {
  implicit val system: ActorSystem
  implicit val mat: Materializer
  implicit val ctx: ExecutionContext
  implicit val scheduler: Scheduler

  private val localMesosUrl = sys.env.get("USE_LOCAL_MESOS")
  lazy val mesosMasterUrl = s"zk://${zkServer.connectUri}/mesos"
  lazy val mesosNumMasters = 1
  lazy val mesosNumSlaves = 1
  lazy val mesosQuorumSize = 1
  lazy val mesosConfig = MesosConfig()
  lazy val mesosLeaderTimeout: FiniteDuration = patienceConfig.timeout.toMillis.milliseconds
  lazy val mesosCluster = MesosCluster(suiteName, mesosNumMasters, mesosNumSlaves, mesosMasterUrl, mesosQuorumSize,
    autoStart = false, config = mesosConfig, mesosLeaderTimeout)
  lazy val mesos = new MesosFacade(localMesosUrl.getOrElse(s"http:${mesosCluster.waitForLeader().futureValue}"))

  override def cleanMesos(): Unit = mesosCluster.clean()

  abstract override def beforeAll(): Unit = {
    super.beforeAll()
    localMesosUrl.fold(mesosCluster.start().futureValue)(identity)
  }

  abstract override def afterAll(): Unit = {
    localMesosUrl.fold(mesosCluster.close())(_ => ())
    super.afterAll()
  }
}
