
package mesosphere.marathon.integration.setup

import java.io.File
import java.util.concurrent.{ Executors, TimeUnit }

import com.google.common.util.concurrent.{ AbstractIdleService, Service }
import com.google.inject.Guice
import mesosphere.chaos.http.{ HttpConf, HttpModule, HttpService }
import mesosphere.chaos.metrics.MetricsModule
import org.apache.commons.io.FileUtils
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory

import scala.collection.immutable.ListMap
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.sys.ShutdownHookThread
import scala.sys.process._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

/**
  * Book Keeper for processes and services.
  * During integration tests, several services and processes have to be launched.
  * The ProcessKeeper knows about them and can handle their lifecycle.
  */
object ProcessKeeper {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)
  private[this] var processes = ListMap.empty[String, Process]
  private[this] var services = List.empty[Service]

  private[this] val ENV_MESOS_WORK_DIR: String = "MESOS_WORK_DIR"

  def startHttpService(port: Int, assetPath: String) = {
    startService {
      log.info(s"Start Http Service on port $port")
      val conf = new ScallopConf(Array("--http_port", port.toString, "--assets_path", assetPath)) with HttpConf {
        verify()
      }
      val injector = Guice.createInjector(new MetricsModule, new HttpModule(conf), new HttpServiceTestModule)
      injector.getInstance(classOf[HttpService])
    }
  }

  def startZooKeeper(port: Int, workDir: String, wipeWorkDir: Boolean = true, superCreds: Option[String] = None) {
    val systemArgs: List[String] = "-Dzookeeper.jmx.log4j.disable=true" :: Nil
    val sd: List[String] = superCreds match {
      case None => Nil
      case Some(userPassword) =>
        val digest = org.apache.zookeeper.server.auth.DigestAuthenticationProvider.generateDigest(userPassword)
        s"-Dzookeeper.DigestAuthenticationProvider.superDigest=$digest" :: Nil
    }
    val app = "org.apache.zookeeper.server.ZooKeeperServerMain" :: port.toString :: workDir :: Nil

    val workDirFile = new File(workDir)
    if (wipeWorkDir) {
      FileUtils.deleteDirectory(workDirFile)
      FileUtils.forceMkdir(workDirFile)
    }

    startJavaProcess("zookeeper", heapInMegs = 512, systemArgs ++ sd ++ app, new File("."),
      sys.env, _.contains("binding to port"))
  }

  def startMesosLocal(port: Int): Process = {
    val mesosWorkDirForMesos: String = "/tmp/marathon-itest-mesos"
    val mesosWorkDirFile: File = new File(mesosWorkDirForMesos)
    FileUtils.deleteDirectory(mesosWorkDirFile)
    FileUtils.forceMkdir(mesosWorkDirFile)

    val mesosEnv = setupMesosEnv(mesosWorkDirFile, mesosWorkDirForMesos)
    startProcess(
      "mesos",
      Process(Seq("mesos-local", "--ip=127.0.0.1", s"--port=$port"), cwd = None, mesosEnv: _*),
      upWhen = _.toLowerCase.contains("registered with master"))
  }

  private[this] def defaultContainerizers: String = {
    if (sys.env.getOrElse("RUN_DOCKER_INTEGRATION_TESTS", "true") == "true") {
      "docker,mesos"
    } else {
      "mesos"
    }
  }
  def setupMesosEnv(workDirFile: File, workDir: String, containerizers: Option[String] = None) = {
    val effectiveContainerizers = containerizers.getOrElse(defaultContainerizers)
    val credentialsPath = write(workDirFile, fileName = "credentials", content = "principal1 secret1")
    val aclsPath = write(workDirFile, fileName = "acls.json", content =
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
      ENV_MESOS_WORK_DIR -> workDir,
      "MESOS_LAUNCHER" -> "posix",
      "MESOS_CONTAINERIZERS" -> effectiveContainerizers,
      "MESOS_ROLES" -> "public,foo",
      "MESOS_ACLS" -> s"file://$aclsPath",
      "MESOS_CREDENTIALS" -> s"file://$credentialsPath")
  }

  def startMesos(processName: String, workDir: String, args: Seq[String], startMessage: String, wipe: Boolean): Process = {
    val workDirFile: File = new File(workDir)
    if (wipe) FileUtils.deleteDirectory(workDirFile)
    FileUtils.forceMkdir(workDirFile)

    val mesosEnv = setupMesosEnv(workDirFile, workDir, containerizers = Some("mesos"))
    startProcess(
      processName,
      Process(args, cwd = None, mesosEnv: _*),
      upWhen = _.toLowerCase.contains(startMessage))
  }

  def startMarathon(cwd: File, env: Map[String, String], arguments: List[String],
    mainClass: String = "mesosphere.marathon.Main",
    startupLine: String = "Started ServerConnector",
    processName: String = "marathon"): Process = {

    val debugArgs = List(
      "-Dakka.loglevel=DEBUG",
      "-Dakka.actor.debug.receive=true",
      "-Dakka.actor.debug.autoreceive=true",
      "-Dakka.actor.debug.lifecycle=true"
    )

    val marathonWorkDir: String = s"/tmp/marathon-itest-$processName"
    val marathonWorkDirFile: File = new File(marathonWorkDir)
    FileUtils.deleteDirectory(marathonWorkDirFile)
    FileUtils.forceMkdir(marathonWorkDirFile)

    val secretPath = write(marathonWorkDirFile, fileName = "marathon-secret", content = "secret1")
    val authSettings = List(
      "--mesos_authentication_principal", "principal1",
      "--mesos_role", "foo",
      "--mesos_authentication_secret_file", s"$secretPath"
    )

    val argsWithMain = mainClass :: arguments ++ authSettings

    startJavaProcess(
      processName, heapInMegs = 1024, /* debugArgs ++ */ argsWithMain, cwd,
      env + (ENV_MESOS_WORK_DIR -> marathonWorkDir),
      upWhen = _.contains(startupLine))
  }

  private[this] def write(dir: File, fileName: String, content: String): String = {
    val file = File.createTempFile(fileName, "", dir)
    file.deleteOnExit()
    FileUtils.write(file, content)
    file.setReadable(true)
    file.getAbsolutePath
  }

  def startJavaProcess(name: String, heapInMegs: Int, arguments: List[String],
    cwd: File = new File("."), env: Map[String, String] = Map.empty, upWhen: String => Boolean): Process = {
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes")
    val memSettings = s"-Xmx${heapInMegs}m"
    // Omit the classpath in order to avoid cluttering the tests output
    log.info(s"Start java process $name with command: ${(javaExecutable :: memSettings :: arguments).mkString(" ")}")
    val command: List[String] = javaExecutable :: memSettings :: "-classpath" :: classPath :: arguments
    val builder = Process(command, cwd, env.toList: _*)
    val process = startProcess(name, builder, upWhen)
    log.info(s"Java process $name up and running!")
    process
  }

  def startProcess(name: String, processBuilder: ProcessBuilder, upWhen: String => Boolean, timeout: Duration = 30.seconds): Process = {
    require(!processes.contains(name), s"Process with $name already started")

    sealed trait ProcessState
    case object ProcessIsUp extends ProcessState
    case object ProcessExited extends ProcessState

    val up = Promise[ProcessIsUp.type]()
    val logger = new ProcessLogger {
      def checkUp(out: String) = {
        log.info(s"$name: $out")
        checkLogFunctions.get(name).foreach(fn => fn.apply(out))
        if (!up.isCompleted && upWhen(out)) up.trySuccess(ProcessIsUp)
      }
      override def buffer[T](f: => T): T = f
      override def out(s: => String) = checkUp(s)
      override def err(s: => String) = checkUp(s)
    }
    val process = processBuilder.run(logger)
    val processExitCode: Future[ProcessExited.type] = Future {
      val exitCode = scala.concurrent.blocking {
        process.exitValue()
      }
      log.info(s"Process $name finished with exit code $exitCode")

      // Sometimes this finishes before the other future finishes parsing the output
      // and we incorrectly report ProcessExited instead of ProcessIsUp as the result of upOrExited.
      Await.result(up.future, 1.second)

      ProcessExited
    }(ExecutionContext.fromExecutor(Executors.newCachedThreadPool()))
    val upOrExited = Future.firstCompletedOf(Seq(up.future, processExitCode))(ExecutionContext.global)
    Try(Await.result(upOrExited, timeout)) match {
      case Success(result) =>
        result match {
          case ProcessExited =>
            throw new IllegalStateException(s"Process $name exited before coming up. Give up. $processBuilder")
          case ProcessIsUp =>
            processes += name -> process
            log.info(s"Process $name is up and running. ${processes.size} processes in total.")
        }
      case Failure(_) =>
        process.destroy()
        throw new IllegalStateException(
          s"Process $name did not come up within time bounds ($timeout). Give up. $processBuilder")
    }
    process
  }

  def onStopServices(block: => Unit): Unit = {
    services ::= new AbstractIdleService {
      override def shutDown(): Unit = {
        block
      }

      override def startUp(): Unit = {}
    }
  }

  val PIDRE = """^\s*(\d+)\s+(\S*)$""".r

  def stopJavaProcesses(wantedMainClass: String): Unit = {
    val pids = "jps -l".!!.split("\n").collect {
      case PIDRE(pid, mainClass) if mainClass.contains(wantedMainClass) => pid
    }
    if (pids.nonEmpty) {
      val killCommand = s"kill -9 ${pids.mkString(" ")}"
      log.warn(s"Left over processes, executing: $killCommand")
      val ret = killCommand.!
      if (ret != 0) {
        log.error(s"kill returned $ret")
      }
    }
  }

  def stopProcess(name: String): Unit = {
    import mesosphere.util.ThreadPoolContext.ioContext
    log.info(s"Stop Process $name")
    processes.get(name).foreach { process =>
      def killProcess: Int = {
        // Unfortunately, there seem to be race conditions in Process.exitValue.
        // Thus this ugly workaround.
        Await.result(Future {
          scala.concurrent.blocking {
            Try(process.destroy())
            process.exitValue()
          }
        }, 5.seconds)
      }
      //retry on fail
      Try(killProcess) recover { case _ => killProcess } match {
        case Success(value) => processes -= name
        case Failure(NonFatal(e)) => log.error("giving up waiting for processes to finish", e)
      }
      log.info(s"Stop Process $name: Done")
    }
  }

  def stopAllProcesses(): Unit = {
    processes.keys.toSeq.reverse.foreach(stopProcess)
    processes = ListMap.empty
  }

  def hasProcess(name: String): Boolean = processes.contains(name)

  def processNames: Set[String] = processes.keySet

  def startService(service: Service): Unit = {
    services ::= service
    service.startAsync().awaitRunning()
  }

  def stopAllServices(): Unit = {
    services.foreach(_.stopAsync())
    services.par.foreach { service =>
      try { service.awaitTerminated(5, TimeUnit.SECONDS) }
      catch {
        case NonFatal(ex) => log.error(s"Could not stop service $service", ex)
      }
    }
    services = Nil
  }

  /**
    * Check log functions are used to analyze the output of the process.
    * See SingleMarathonIntegrationTest.waitForProcessLogMessage
    */
  var checkLogFunctions: Map[String, String => Boolean] = Map.empty
  def addCheck(process: String, fn: String => Boolean): Unit = checkLogFunctions += process -> fn
  def removeCheck(process: String) = checkLogFunctions -= process

  def shutdown(): Unit = {
    log.info(s"Cleaning up Processes $processes and Services $services")
    stopAllProcesses()
    stopAllServices()
    log.info(s"Cleaning up Processes $processes and Services $services: Done")
  }

  def exitValue(processName: String): Int = {
    processes(processName).exitValue()
  }

  val shutDownHook: ShutdownHookThread = sys.addShutdownHook {
    shutdown()
  }
}

