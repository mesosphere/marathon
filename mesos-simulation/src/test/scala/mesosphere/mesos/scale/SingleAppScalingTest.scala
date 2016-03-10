package mesosphere.mesos.scale

import java.io.File

import mesosphere.marathon.api.v2.json.{ AppUpdate }
import mesosphere.marathon.integration.facades.{ ITDeploymentResult, MarathonFacade }
import mesosphere.marathon.integration.setup._
import MarathonFacade._
import mesosphere.marathon.state.{ AppDefinition, PathId }
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }
import org.slf4j.LoggerFactory
import play.api.libs.json._

import scala.concurrent.duration._

object SingleAppScalingTest {
  val metricsFile = "scaleUp20s-metrics"
  val appInfosFile = "scaleUp20s-appInfos"
}

class SingleAppScalingTest
    extends IntegrationFunSuite
    with SingleMarathonIntegrationTest
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen {

  private[this] val log = LoggerFactory.getLogger(getClass)

  //clean up state before running the test case
  before(cleanUp())

  override def startMarathon(port: Int, args: String*): Unit = {
    val cwd = new File(".")

    val maxTasksPerOffer = Option(System.getenv("MARATHON_MAX_TASKS_PER_OFFER")).getOrElse("1")

    ProcessKeeper.startMarathon(cwd, env,
      List("--http_port", port.toString,
        "--zk", config.zk,
        "--max_tasks_per_offer", maxTasksPerOffer,
        "--task_launch_timeout", "20000",
        "--task_launch_confirm_timeout", "1000") ++ args.toList,
      mainClass = "mesosphere.mesos.simulation.SimulateMesosMain")
  }

  private[this] def createStopApp(instances: Int): Unit = {
    Given("a new app")
    val appIdPath: PathId = testBasePath / "/test/app"
    val app = appProxy(appIdPath, "v1", instances = instances, withHealth = false)

    When("the app gets posted")
    val createdApp: RestResult[AppDefinition] = marathon.createAppV2(app)
    createdApp.code should be(201) // created
    val deploymentIds: Seq[String] = extractDeploymentIds(createdApp)
    deploymentIds.length should be(1)
    val deploymentId = deploymentIds.head

    Then("the deployment should finish eventually")
    waitForDeploymentId(deploymentId, (30 + instances).seconds)

    When("deleting the app")
    val deleteResult: RestResult[ITDeploymentResult] = marathon.deleteApp(appIdPath, force = true)

    Then("the delete should finish eventually")
    waitForChange(deleteResult)
  }

  test("create/stop app with 1 instance (does it work?)") {
    createStopApp(1)
  }

  test("create/stop app with 100 instances (warm up)") {
    createStopApp(100)
  }

  test("application scaling") {
    // This test has a lot of logging output. Thus all log statements are prefixed with XXX
    // for better grepability.

    val appIdPath = testBasePath / "/test/app"
    val appWithManyInstances = appProxy(appIdPath, "v1", instances = 100000, withHealth = false)
    val response = marathon.createAppV2(appWithManyInstances)
    log.info(s"XXX ${response.originalResponse.status}: ${response.originalResponse.entity}")

    val startTime = System.currentTimeMillis()
    var metrics = Seq.newBuilder[JsValue]
    var appInfos = Seq.newBuilder[JsValue]

    for (i <- 1 to 20) {
      val waitTime: Long = startTime + i * 1000 - System.currentTimeMillis()
      if (waitTime > 0) {
        Thread.sleep(waitTime)
      }
      //      val currentApp = marathon.app(appIdPath)
      val appJson =
        (marathon.listAppsInBaseGroup.entityJson \ "apps")
          .as[Seq[JsObject]]
          .filter { appJson => (appJson \ "id").as[String] == appIdPath.toString }
          .head

      val instances = (appJson \ "instances").as[Int]
      val tasksRunning = (appJson \ "tasksRunning").as[Int]
      val tasksStaged = (appJson \ "tasksStaged").as[Int]

      log.info(s"XXX (starting) Current instance count: staged $tasksStaged, running $tasksRunning / $instances")

      appInfos += ScalingTestResultFiles.addTimestamp(startTime)(appJson)
      metrics += ScalingTestResultFiles.addTimestamp(startTime)(marathon.metrics().entityJson)
    }

    ScalingTestResultFiles.writeJson(SingleAppScalingTest.appInfosFile, appInfos.result())
    ScalingTestResultFiles.writeJson(SingleAppScalingTest.metricsFile, metrics.result())

    log.info("XXX suspend")
    val result = marathon.updateApp(appWithManyInstances.id, AppUpdate(instances = Some(0)), force = true).originalResponse
    log.info(s"XXX ${result.status}: ${result.entity}")

    WaitTestSupport.waitFor("app suspension", 10.seconds) {
      val currentApp = marathon.app(appIdPath)

      val instances = (currentApp.entityJson \ "app" \ "instances").as[Int]
      val tasksRunning = (currentApp.entityJson \ "app" \ "tasksRunning").as[Int]
      val tasksStaged = (currentApp.entityJson \ "app" \ "tasksStaged").as[Int]

      log.info(s"XXX (suspendSuccessfully) Current instance count: staged $tasksStaged, running $tasksRunning / $instances")

      if (instances == 0) {
        Some(())
      }
      else {
        // slow down
        Thread.sleep(1000)
        None
      }
    }

    log.info("XXX deleting")
    val deleteResult: RestResult[ITDeploymentResult] = marathon.deleteApp(appWithManyInstances.id, force = true)
    waitForChange(deleteResult)
  }
}
