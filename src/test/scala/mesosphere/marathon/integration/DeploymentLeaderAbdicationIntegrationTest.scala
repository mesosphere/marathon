package mesosphere.marathon.integration

import java.io.File
import java.util.UUID

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.integration.setup.{ ServiceMock, ServiceMockFacade, WaitTestSupport }
import mesosphere.marathon.state.{ AppDefinition, PathId }
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._

abstract class DeploymentLeaderAbdicationIntegrationTest extends LeaderIntegrationTest with StrictLogging {

  def test(appId: PathId, createApp: AppDefinition, updateApp: AppUpdate) = {

    // this test asks the leader to abdicate at some point. this var has to be re-set after that
    // because the initial process is not accessible afterwards
    var marathonFacade = firstRunningProcess.client

    Given("a new simple app with 2 instances")
    createApp.instances shouldBe 2

    val created = marathonFacade.createAppV2(createApp)
    created.code should be (201)
    waitForDeployment(created)

    logger.debug(s"Started app: ${marathonFacade.app(appId).entityPrettyJsonString}")

    When("updating the app")
    val appV2 = marathonFacade.updateApp(appId, updateApp)

    And("new and updated tasks are started successfully")
    val updated = waitForTasks(appId, 4) //make sure, the new task has really started

    val newVersion = appV2.value.version.toString
    val updatedTasks = updated.filter(_.version.contains(newVersion))
    val updatedTaskIds: List[String] = updatedTasks.map(_.id)
    updatedTaskIds should have size 2

    logger.debug(s"Updated app: ${marathonFacade.app(appId).entityPrettyJsonString}")

    And("first updated task becomes green")
    val serviceFacade1 = ServiceMockFacade(marathonFacade.tasks(appId).value) { task =>
      task.version.contains(newVersion) && task.launched
    }
    serviceFacade1.continue()

    When("marathon leader is forced to abdicate")
    val result = marathonFacade.abdicate()
    result.code should be (200)

    And("a new leader is elected")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstRunningProcess.client.leader().code == 200 }
    marathonFacade = firstRunningProcess.client

    And("second updated task becomes healthy")
    val serviceFacade2 = ServiceMockFacade(marathonFacade.tasks(appId).value) { task =>
      task.version.contains(newVersion) && task.launched && task != serviceFacade1.task
    }
    serviceFacade2.continue()

    Then("the app should have only 2 tasks launched")
    waitForTasks(appId, 2)(marathonFacade) should have size 2

    And("app was deployed successfully")
    waitForDeployment(appV2)

    val after = marathonFacade.tasks(appId)
    val afterTaskIds = after.value.map(_.id)
    logger.debug(s"App after restart: ${marathonFacade.app(appId).entityPrettyJsonString}")

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
