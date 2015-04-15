package mesosphere.marathon.integration

import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.{ PathId, AppDefinition }
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }
import org.slf4j.LoggerFactory
import play.api.libs.json.JsArray
import scala.util.control.NonFatal

class AppDeployIntegrationTest
    extends IntegrationFunSuite
    with SingleMarathonIntegrationTest
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen {

  private[this] val log = LoggerFactory.getLogger(getClass)

  //clean up state before running the test case
  before(cleanUp())

  def extractDeploymentIds(app: RestResult[AppDefinition]): Seq[String] = {
    for (deployment <- (app.entityJson \ "deployments").as[JsArray].value)
      yield (deployment \ "id").as[String]
  }

  private def createAndDeployAnAppWithTwoTasksImpl(): Unit = {
    Given("a new app")
    log.info("new app")
    val appIdPath: PathId = testBasePath / "/test/app"
    val appId: String = appIdPath.toString
    val app = appProxy(appIdPath, "v1", instances = 2, withHealth = false)

    When("the app gets posted")
    log.info("new app")
    val createdApp: RestResult[AppDefinition] = marathon.createApp(app)

    Then("the app is created and a success event arrives eventually")
    log.info("new app")
    createdApp.code should be(201) // created

    Then("we get various events until deployment success")
    val deploymentIds: Seq[String] = extractDeploymentIds(createdApp)
    deploymentIds.length should be(1)
    val deploymentId = deploymentIds.head

    val apiPostEvent = waitForEvent("api_post_event")
    apiPostEvent.info("appDefinition").asInstanceOf[Map[String, Any]]("id").asInstanceOf[String] should
      be(appId)

    val groupChangeSuccess = waitForEvent("group_change_success")
    groupChangeSuccess.info("groupId").asInstanceOf[String] should be(appIdPath.parent.toString)

    waitForEvent("deployment_info")

    val taskUpdate1: CallbackEvent = waitForEvent("status_update_event")
    taskUpdate1.info("appId").asInstanceOf[String] should be(appId)

    val taskUpdate2 = waitForEvent("status_update_event")
    taskUpdate2.info("appId").asInstanceOf[String] should be(appId)

    log.info("waiting for deployment success")
    val deploymentSuccess = waitForEvent("deployment_success")
    deploymentSuccess.info("id") should be(deploymentId)

    Then("after that deployments should be empty")
    val event: RestResult[List[Deployment]] = marathon.listDeploymentsForBaseGroup()
    event.value should be('empty)

    Then("Both tasks respond to http requests")
    def pingTask(taskInfo: CallbackEvent): RestResult[String] = {
      val host: String = taskInfo.info("host").asInstanceOf[String]
      val port: Int = taskInfo.info("ports").asInstanceOf[Seq[Int]].head
      appMock.ping(host, port)
    }

    pingTask(taskUpdate1).entityString should be(s"Pong $appId\n")
    pingTask(taskUpdate2).entityString should be(s"Pong $appId\n")
  }

  test("create and deploy an app with two tasks") {
    try {
      createAndDeployAnAppWithTwoTasksImpl()
    }
    catch {
      case NonFatal(e) =>
        log.error("Error, sleeping for a while for analysis", e)
        Thread.sleep(120000)
        throw e
    }
  }
}
