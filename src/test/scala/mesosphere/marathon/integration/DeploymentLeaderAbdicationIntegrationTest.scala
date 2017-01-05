package mesosphere.marathon.integration

import java.io.File
import java.util.UUID

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.integration.setup.{ ServiceMock, ServiceMockFacade, WaitTestSupport }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._
import scala.util.Try

abstract class DeploymentLeaderAbdicationIntegrationTest extends LeaderIntegrationTest with StrictLogging {

  def test(appId: PathId, createApp: AppDefinition, updateApp: AppUpdate) = {
    Given("a new simple app with 2 instances")
    createApp.instances shouldBe 2

    val created = marathon.createAppV2(createApp)
    created.code should be (201)
    waitForDeployment(created)

    val started = marathon.tasks(appId)
    logger.debug(s"Started app: ${marathon.app(appId).entityPrettyJsonString}")

    When("updating the app")
    val appV2 = marathon.updateApp(appId, updateApp)

    And("new and updated tasks are started successfully")
    val updated = waitForTasks(appId, 4) //make sure, the new task has really started

    val updatedTasks = updated.filter(_.version.getOrElse("none") == appV2.value.version.toString)
    val updatedTaskIds: List[String] = updatedTasks.map(_.id)
    updatedTaskIds should have size 2

    logger.debug(s"Updated app: ${marathon.app(appId).entityPrettyJsonString}")

    And("first updated task becomes green")
    val serviceFacade1 = new ServiceMockFacade(updatedTasks.head)
    WaitTestSupport.waitUntil("ServiceMock1 is up", 30.seconds){ Try(serviceFacade1.plan()).isSuccess }
    serviceFacade1.continue()

    When("marathon leader is abdicated")
    val result = firstRunningProcess.client.abdicate()
    result.code should be (200)

    And("a new leader is elected")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstRunningProcess.client.leader().code == 200 }

    And("second updated task becomes healthy")
    val serviceFacade2 = new ServiceMockFacade(updatedTasks.last)
    WaitTestSupport.waitUntil("ServiceMock is up", 30.seconds){ Try(serviceFacade2.plan()).isSuccess }
    serviceFacade2.continue()

    Then("the app should have only 2 tasks launched")
    waitForTasks(appId, 2)(firstRunningProcess.client) should have size 2

    And("app was deployed successfully")
    waitForDeployment(appV2)

    val after = firstRunningProcess.client.tasks(appId)
    val afterTaskIds = after.value.map(_.id)
    logger.debug(s"App after restart: ${firstRunningProcess.client.app(appId).entityPrettyJsonString}")

    And("taskIds after restart should be equal to the updated taskIds (not started ones)")
    afterTaskIds.sorted should equal (updatedTaskIds.sorted)
  }

  /**
    * Create a shell script that can start a service mock
    */
  lazy val serviceMockScript: String = {
    val uuid = UUID.randomUUID.toString
    appProxyIds(_ += uuid)
    val javaExecutable = sys.props.get("java.home").fold("java")(_ + "/bin/java")
    val classPath = sys.props.getOrElse("java.class.path", "target/classes").replaceAll(" ", "")
    val main = classOf[ServiceMock].getName
    val run = s"""$javaExecutable -DappProxyId=$uuid -DtestSuite=$suiteName -Xmx64m -classpath $classPath $main"""
    val file = File.createTempFile("serviceProxy", ".sh")
    file.deleteOnExit()

    FileUtils.write(
      file,
      s"""#!/bin/sh
         |set -x
         |exec $run $$*""".stripMargin)
    file.setExecutable(true)
    file.getAbsolutePath
  }
}
