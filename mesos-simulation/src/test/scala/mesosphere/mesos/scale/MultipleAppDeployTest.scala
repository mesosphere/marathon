package mesosphere.mesos.scale

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.IntegrationTest
import mesosphere.marathon.integration.facades.{ ITDeploymentResult, MarathonFacade }
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.raml.GroupUpdate
import mesosphere.marathon.state.PathId
import org.scalatest.concurrent.Eventually
import org.slf4j.LoggerFactory
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

object MultipleAppDeployTest {
  val metricsFile = "deploy-group-20s-metrics"
}

@IntegrationTest
class MultipleAppDeployTest extends AkkaIntegrationTest with ZookeeperServerTest with SimulatedMesosTest with MarathonTest with Eventually {

  val maxInstancesPerOffer: String = Option(System.getenv("MARATHON_MAX_INSTANCES_PER_OFFER")).getOrElse("1")

  lazy val marathonServer = LocalMarathon(autoStart = false, suiteName = suiteName, "localhost:5050", zkUrl = s"zk://${zkServer.connectUri}/marathon", conf = Map(
    "max_instances_per_offer" -> maxInstancesPerOffer,
    "task_launch_timeout" -> "20000",
    "task_launch_confirm_timeout" -> "1000"),
    mainClass = "mesosphere.mesos.simulation.SimulateMesosMain")

  override lazy val leadingMarathon: Future[LocalMarathon] = Future.successful(marathonServer)

  override lazy val marathonUrl: String = s"http://localhost:${marathonServer.httpPort}"
  override lazy val testBasePath: PathId = PathId.empty
  override lazy val marathon: MarathonFacade = new MarathonFacade(marathonUrl, testBasePath)

  private[this] val log = LoggerFactory.getLogger(getClass)

  override def beforeAll(): Unit = {
    super.beforeAll()
    marathonServer.start().futureValue
    val sseStream = startEventSubscriber()
    system.registerOnTermination(sseStream.cancel())
    waitForSSEConnect()
  }

  override def afterAll(): Unit = {
    // intentionally cleaning up before we print out the stats.
    super.afterAll()
    marathonServer.close()
    println()
    DisplayAppScalingResults.displayMetrics(MultipleAppDeployTest.metricsFile)
  }

  val numberOfNestedObjects = 10
  val rootGroupId: PathId = testBasePath / "/test/group"

  def createGroup(depth: Int, groupId: PathId = rootGroupId): GroupUpdate = {
    GroupUpdate(
      id = Some(groupId.toString),
      apps = if (depth == 0) Some((1 to numberOfNestedObjects).map(i => {
        appProxy(groupId / s"a$i", "v2", numberOfNestedObjects, None)
      }).toSet)
      else Some(Set()),
      groups = if (depth == 0) Some(Set()) else Some(
        (1 to numberOfNestedObjects).map(i => {
        createGroup(depth - 1, groupId / s"g$i")
      }).toSet)
    )
  }

  private[this] def createStopGroup(depth: Int): Unit = {
    Given("a new group")
    val group = createGroup(depth)

    When("the group gets posted")
    val createdGroup = marathon.updateGroup(rootGroupId, group, true)
    log.info(createdGroup.entityPrettyJsonString)
    createdGroup.code should be(200) // created
    val deploymentId: String = (createdGroup.entityJson \ "deploymentId").as[String]

    Then("the deployment should finish eventually")
    waitForDeploymentId(deploymentId, (30 + Math.pow(numberOfNestedObjects, depth + 1)).seconds)

    When("deleting the app")
    val deleteResult: RestResult[ITDeploymentResult] = marathon.deleteGroup(rootGroupId, force = true)

    Then("the delete should finish eventually")
    waitForDeployment(deleteResult)
  }

  "DeployMultipleGroups" should {
    "create/stop group with depth 0 (does it work?)" in {
      createStopGroup(0)
    }

    "create/stop group with depth 1 (warm up)" in {
      createStopGroup(1)
    }

    "huge group deploying" in {
      // This test has a lot of logging output. Thus all log statements are prefixed with XXX
      // for better grepability.

      val group = createGroup(3)

      val response = marathon.updateGroup(rootGroupId, group, true)
      log.info(s"XXX ${marathonServer.httpPort}")
      log.info(s"XXX ${response.originalResponse.status}: ${response.originalResponse.entity}")

      val startTime = System.currentTimeMillis()
      var metrics = Seq.newBuilder[JsValue]

      for (i <- 1 to 20) {
        val waitTime: Long = startTime + i * 1000 - System.currentTimeMillis()
        if (waitTime > 0) {
          Thread.sleep(waitTime)
        }

        val tasksMetrics =
          (marathon.listAppsInBaseGroup.entityJson \ "apps")
            .as[Seq[JsObject]]
            .map { appJson =>
              (
                (appJson \ "instances").as[Int],
                (appJson \ "tasksRunning").as[Int],
                (appJson \ "tasksStaged").as[Int])
            }
            .reduceOption((x, y) => (x._1 + y._1, x._2 + y._2, x._3 + y._3))
            .getOrElse((0, 0, 0))

        val instances = tasksMetrics._1
        val tasksRunning = tasksMetrics._2
        val tasksStaged = tasksMetrics._3

        log.info(s"XXX (starting) Current instance count: staged $tasksStaged, running $tasksRunning / $instances")

        metrics += ScalingTestResultFiles.addTimestamp(startTime)(marathon.metrics().entityJson)
      }

      val deploymentId: String = (response.entityJson \ "deploymentId").as[String]
      Then("the deployment should finish eventually")
      waitForDeploymentId(deploymentId, 60.seconds)

      ScalingTestResultFiles.writeJson(MultipleAppDeployTest.metricsFile, metrics.result())

      eventually {
        val tasksMetrics =
          (marathon.listAppsInBaseGroup.entityJson \ "apps")
            .as[Seq[JsObject]]
            .map { appJson =>
              (
                (appJson \ "instances").as[Int],
                (appJson \ "tasksRunning").as[Int],
                (appJson \ "tasksStaged").as[Int])
            }
            .reduceOption((x, y) => (x._1 + y._1, x._2 + y._2, x._3 + y._3))
            .getOrElse((0, 0, 0))

        val instances = tasksMetrics._1
        val tasksRunning = tasksMetrics._2
        val tasksStaged = tasksMetrics._3

        log.info(s"XXX (starting) Current instance count: staged $tasksStaged, running $tasksRunning / $instances")
        metrics += ScalingTestResultFiles.addTimestamp(startTime)(marathon.metrics().entityJson)
        require(instances == tasksRunning)
      }

      log.info("XXX delete")
      val result = marathon.deleteGroup(PathId("/"), force = true).originalResponse
      log.info(s"XXX ${result.status}: ${result.entity}")

      eventually {
        val tasksMetrics =
          (marathon.listAppsInBaseGroup.entityJson \ "apps")
            .as[Seq[JsObject]]
            .map { appJson =>
              (
                (appJson \ "instances").as[Int],
                (appJson \ "tasksRunning").as[Int],
                (appJson \ "tasksStaged").as[Int])
            }
            .reduceOption((x, y) => (x._1 + y._1, x._2 + y._2, x._3 + y._3))
            .getOrElse((0, 0, 0))

        val instances = tasksMetrics._1
        val tasksRunning = tasksMetrics._2
        val tasksStaged = tasksMetrics._3

        log.info(s"XXX (starting) Current instance count: staged $tasksStaged, running $tasksRunning / $instances")

        require(tasksRunning == 0)
      }
    }
  }
}
