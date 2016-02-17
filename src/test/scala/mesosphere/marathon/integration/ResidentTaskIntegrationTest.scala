package mesosphere.marathon.integration

import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.integration.setup.MarathonFacade._
import mesosphere.marathon.integration.setup.{ SingleMarathonIntegrationTest, IntegrationFunSuite }
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => Mesos }
import org.scalatest.{ GivenWhenThen, BeforeAndAfter, Matchers }
import org.slf4j.LoggerFactory
import scala.collection.immutable.Seq

class ResidentTaskIntegrationTest
    extends IntegrationFunSuite
    with SingleMarathonIntegrationTest
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen {

  private[this] val log = LoggerFactory.getLogger(getClass)

  //clean up state before running the test case
  before(cleanUp())

  // FIXME (217) enable the test when the base functionality is implemented
  ignore("persistent volume will be re-attached and keep state") {
    import StatusUpdateEvent._

    val f = new Fixture
    val appId = testBasePath / "app"
    val containerPath = "persistent-volume"

    Given("A task that writes into a persistent volume")
    create(f.appWithPersistentVolume(
      appId,
      containerPath,
      cmd = s"""echo "data" > $containerPath/data """))

    // wait until the task exited
    waitForStatusUpdates(TASK_RUNNING, TASK_FINISHED)

    // suspend the app
    suspend(appId)

    When("We start a new task that checks for written data")
    // deploy a new version that checks for the data written the above step
    marathon.updateApp(
      appId,
      AppUpdate(
        instances = Some(1),
        cmd = Some(s"""if [ -e $containerPath/data ] ; then exit 0 ; else exit 1 ; fi ;""")))

    Then("We expect the task to successfully check for data within the persistent volume")
    waitForStatusUpdates(TASK_RUNNING, TASK_FINISHED)
  }

  object Event {
    val STATUS_UPDATE_EVENT = "status_update_event"
  }

  object StatusUpdateEvent {
    val TASK_FINISHED = "TASK_FINISHED"
    val TASK_RUNNING = "TASK_RUNNING"
    val TASK_FAILED = "TASK_FAILED"
  }

  def waitForStatusUpdates(kinds: String*) = {
    val updateEvents = kinds.map(_ => Event.STATUS_UPDATE_EVENT)
    val events = waitForEvents(updateEvents: _*)()
    val statuses = events.values.flatMap(_.map(_.info("taskStatus")))
    statuses should equal(kinds.toSeq)
  }

  private[this] def create(app: AppDefinition) = {
    val result = marathon.createAppV2(app)
    result.code should be (201) //Created
    extractDeploymentIds(result) should have size 1
  }

  private[this] def suspend(appId: PathId) = {
    val updateResult = marathon.updateApp(appId, AppUpdate(instances = Some(0)), force = true)
    updateResult.code shouldBe 200
    waitForTasks(appId, 0) //make sure there are no more tasks
  }

  class Fixture {
    def appWithPersistentVolume(appId: PathId, containerPath: String, cmd: String): AppDefinition = {
      val persistentVolume: Volume = PersistentVolume(
        containerPath = containerPath,
        persistent = PersistentVolumeInfo(size = 1),
        mode = Mesos.Volume.Mode.RW
      )

      appProxy(appId, "v1", instances = 1, withHealth = false).copy(
        container = Some(Container(
          `type` = Mesos.ContainerInfo.Type.MESOS,
          volumes = Seq(persistentVolume)
        )),
        cmd = Some(cmd),
        executor = ""
      )
    }

  }

}