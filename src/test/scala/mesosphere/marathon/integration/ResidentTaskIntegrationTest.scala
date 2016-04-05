package mesosphere.marathon.integration

import mesosphere.marathon.Protos
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.integration.facades.MesosFacade.{ ITResources, ITMesosState }
import mesosphere.marathon.integration.facades.{ ITEnrichedTask, MarathonFacade }
import MarathonFacade._
import mesosphere.marathon.integration.setup.{ RestResult, IntegrationFunSuite, SingleMarathonIntegrationTest }
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => Mesos }
import org.scalatest.{ Tag, BeforeAndAfter, GivenWhenThen, Matchers }
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.util.Try
import scala.concurrent.duration._

class ResidentTaskIntegrationTest
    extends IntegrationFunSuite
    with SingleMarathonIntegrationTest
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen {

  import Fixture._

  private[this] val log = LoggerFactory.getLogger(getClass)

  //clean up state before running the test case
  before(cleanUp())

  test("resident task can be deployed and write to persistent volume") { f =>
    Given("An app that writes into a persistent volume")
    val containerPath = "persistent-volume"
    val app = f.residentApp(
      containerPath = containerPath,
      cmd = s"""echo "data" > $containerPath/data""")

    When("A task is launched")
    f.createAsynchronously(app)

    Then("It writes successfully to the persistent volume and finishes")
    waitForStatusUpdates(StatusUpdate.TASK_RUNNING)
    waitForEvent(Event.DEPLOYMENT_SUCCESS)
    waitForStatusUpdates(StatusUpdate.TASK_FINISHED)
  }

  test("resident task can be deployed along with constraints") { f =>
    // background: Reserved tasks may not be considered while making sure constraints are met, because they
    // would prevent launching a task because there `is` already a task (although not launched)
    Given("A resident app that uses a hostname:UNIQUE constraints")
    val containerPath = "persistent-volume"
    val unique = Protos.Constraint.newBuilder
      .setField("hostname")
      .setOperator(Protos.Constraint.Operator.UNIQUE)
      .setValue("")
      .build

    val app = f.residentApp(
      containerPath = containerPath,
      cmd = s"""sleep 1""",
      constraints = Set(unique))

    When("A task is launched")
    f.createAsynchronously(app)

    Then("It it successfully launched")
    waitForStatusUpdates(StatusUpdate.TASK_RUNNING)
    waitForEvent(Event.DEPLOYMENT_SUCCESS)
  }

  test("persistent volume will be re-attached and keep state") { f =>
    Given("An app that writes into a persistent volume")
    val containerPath = "persistent-volume"
    val app = f.residentApp(
      containerPath = containerPath,
      cmd = s"""echo "data" > $containerPath/data """)

    When("a task is launched")
    f.createAsynchronously(app)

    Then("it successfully writes to the persistent volume and then finishes")
    waitForStatusUpdates(StatusUpdate.TASK_RUNNING)
    waitForEvent(Event.DEPLOYMENT_SUCCESS)
    waitForStatusUpdates(StatusUpdate.TASK_FINISHED)

    When("the app is suspended")
    f.suspendSuccessfully(app.id)

    And("a new task is started that checks for the previously written file")
    // deploy a new version that checks for the data written the above step

    marathon.updateApp(
      app.id,
      AppUpdate(
        instances = Some(1),
        cmd = Some(s"""test -e $containerPath/data"""),
        // FIXME: we need to retry starting tasks since there is a race-condition in Mesos,
        // probably related to our recycling of the task ID (but unconfirmed)
        backoff = Some(300.milliseconds)
      )
    ).code shouldBe 200
    // we do not wait for the deployment to finish here to get the task events

    Then("the new task verifies that the persistent volume file is still there")
    waitForStatusUpdates(StatusUpdate.TASK_RUNNING)
    waitForEvent(Event.DEPLOYMENT_SUCCESS)
    waitForStatusUpdates(StatusUpdate.TASK_FINISHED)
  }

  test("resident task is launched completely on reserved resources") { f =>
    Given("A resident app")
    val app = f.residentApp(portDefinitions = Seq.empty /* prevent problems by randomized port assignment */ )

    When("A task is launched")
    f.createSuccessfully(app)

    Then("used and reserved resources correspond to the app")
    val state: RestResult[ITMesosState] = mesos.state

    withClue("used_resources") {
      state.value.agents.head.usedResources should equal(f.itMesosResources)
    }
    withClue("reserved_resources") {
      state.value.agents.head.reservedResourcesByRole.get("foo") should equal(Some(f.itMesosResources))
    }

    When("the app is suspended")
    f.suspendSuccessfully(app.id)

    Then("there are no used resources anymore but there are the same reserved resources")
    val state2: RestResult[ITMesosState] = mesos.state

    withClue("used_resources") {
      state2.value.agents.head.usedResources should be(empty)
    }
    withClue("reserved_resources") {
      state2.value.agents.head.reservedResourcesByRole.get("foo") should equal(Some(f.itMesosResources))
    }

    // we check for a blank slate of mesos reservations after each test
    // TODO: Once we wait for the unreserves before finishing the StopApplication deployment step,
    // we should test that here
  }

  test("Scale Up") { f =>
    Given("A resident app with 0 instances")
    val app = f.createSuccessfully(f.residentApp(instances = 0))

    When("We scale up to 5 instances")
    f.scaleToSuccessfully(app.id, 5)

    Then("exactly 5 tasks have been created")
    f.launchedTasks(app.id).size shouldBe 5
  }

  test("Scale Down") { f =>
    Given("a resident app with 5 instances")
    val app = f.createSuccessfully(f.residentApp(instances = 5))

    When("we scale down to 0 instances")
    f.suspendSuccessfully(app.id)

    Then("all tasks are suspended")
    val all = f.allTasks(app.id)
    all.size shouldBe 5
    all.count(_.launched) shouldBe 0
    all.count(_.suspended) shouldBe 5
  }

  test("Restart") { f =>
    Given("a resident app with 5 instances")
    val app = f.createSuccessfully(
      f.residentApp(
        instances = 5,
        // FIXME: we need to retry starting tasks since there is a race-condition in Mesos,
        // probably related to our recycling of the task ID (but unconfirmed)
        backoffDuration = 300.milliseconds
      )
    )

    When("we restart the app")
    val newVersion = f.restartSuccessfully(app)
    val all = f.allTasks(app.id)

    log.info("tasks after relaunch: {}", all.mkString(";"))

    Then("no extra task was created")
    all.size shouldBe 5

    And("exactly 5 instances are running")
    all.count(_.launched) shouldBe 5

    And("all 5 tasks are restarted and of the new version")
    all.map(_.version).forall(_.contains(newVersion)) shouldBe true
  }

  test("Config Change") { f =>
    Given("a resident app with 5 instances")
    val app = f.createSuccessfully(
      f.residentApp(
        instances = 5,
        // FIXME: we need to retry starting tasks since there is a race-condition in Mesos,
        // probably related to our recycling of the task ID (but unconfirmed)
        backoffDuration = 300.milliseconds
      )
    )

    When("we change the config")
    val newVersion = f.updateSuccessfully(app.id, AppUpdate(cmd = Some("sleep 1234"))).toString
    val all = f.allTasks(app.id)

    log.info("tasks after config change: {}", all.mkString(";"))

    Then("no extra task was created")
    all should have size (5)

    And("exactly 5 instances are running")
    all.filter(_.launched) should have size (5)

    And("all 5 tasks are of the new version")
    all.map(_.version).forall(_.contains(newVersion)) shouldBe true
  }

  /**
    * FIXME (3043): implement the following tests. TASK_LOST can be induced when launching a task with permission:
    *
    * When a framework launches a task, “run_tasks” ACLs are checked to see if the framework
    * (FrameworkInfo.principal) is authorized to run the task/executor as the given user. If not authorized,
    * the launch is rejected and the framework gets a TASK_LOST.
    *
    * (From http://mesos.apache.org/documentation/latest/authorization/)
    */

  ignore("taskLostBehavior = RELAUNCH_AFTER_TIMEOUT, timeout = 10s") { f =>
    Given("A resident app with 1 instance")
    When("The task is lost")
    Then("The task is not relaunched within the timeout")
    And("The task is relaunched with a new Id after the timeout")
  }

  ignore("taskLostBehavior = WAIT_FOREVER") { f =>
    Given("A resident app with 1 instance")
    When("The task is lost")
    Then("No timeout is scheduled") // can we easily verify this?
    And("The task is not relaunched") // can we verify this without waiting?
  }

  ignore("relaunchEscalationTimeoutSeconds = 5s") { f =>
    Given("A resident app with 1 instance")
    When("The task terminates")
    And("We don't get an offer within the timeout")
    Then("We launch a new task on any matching offer")
  }

  private[this] def test(testName: String, testTags: Tag*)(testFun: (Fixture) => Unit): Unit = {
    super.test(testName, testTags: _*)(testFun(new Fixture))
  }

  private[this] def ignore(testName: String, testTags: Tag*)(testFun: (Fixture) => Unit): Unit = {
    super.ignore(testName, testTags: _*)(testFun(new Fixture))
  }

  class Fixture {

    val cpus: Double = 0.001
    val mem: Double = 1.0
    val disk: Double = 1.0
    val persistentVolumeSize: Long = 2

    val itMesosResources = ITResources("mem" -> mem, "cpus" -> cpus, "disk" -> (disk + persistentVolumeSize))

    def residentApp(
      containerPath: String = "persistent-volume",
      cmd: String = "sleep 1000",
      instances: Int = 1,
      backoffDuration: FiniteDuration = 1.hour,
      portDefinitions: Seq[PortDefinition] = PortDefinitions(0),
      constraints: Set[Protos.Constraint] = Set.empty[Protos.Constraint]): AppDefinition = {

      val appId: PathId = PathId(s"/$testBasePath/app-${IdGenerator.generate()}")

      val persistentVolume: Volume = PersistentVolume(
        containerPath = containerPath,
        persistent = PersistentVolumeInfo(size = persistentVolumeSize),
        mode = Mesos.Volume.Mode.RW
      )

      val app = AppDefinition(
        appId,
        instances = instances,
        residency = Some(Residency(
          Residency.defaultRelaunchEscalationTimeoutSeconds,
          Residency.defaultTaskLostBehaviour
        )),
        constraints = constraints,
        container = Some(Container(
          `type` = Mesos.ContainerInfo.Type.MESOS,
          volumes = Seq(persistentVolume)
        )),
        cmd = Some(cmd),
        executor = "",
        // cpus, mem and disk are really small because otherwise we'll soon run out of reservable resources
        cpus = cpus,
        mem = mem,
        disk = disk,
        portDefinitions = portDefinitions,
        backoff = backoffDuration,
        upgradeStrategy = UpgradeStrategy(0.5, 0.0)
      )

      app
    }

    def createSuccessfully(app: AppDefinition): AppDefinition = {
      createAsynchronously(app)
      waitForEvent(Event.DEPLOYMENT_SUCCESS)
      app
    }

    def createAsynchronously(app: AppDefinition): AppDefinition = {
      val result = marathon.createAppV2(app)
      result.code should be(201) //Created
      extractDeploymentIds(result) should have size 1
      app
    }

    def scaleToSuccessfully(appId: PathId, instances: Int): Iterable[ITEnrichedTask] = {
      val result = marathon.updateApp(appId, AppUpdate(instances = Some(instances)))
      result.code should be (200) // OK
      waitForEvent(Event.DEPLOYMENT_SUCCESS)
      waitForTasks(appId, instances)
    }

    def suspendSuccessfully(appId: PathId): Iterable[ITEnrichedTask] = scaleToSuccessfully(appId, 0)

    def updateSuccessfully(appId: PathId, update: AppUpdate): VersionString = {
      val result = marathon.updateApp(appId, update)
      result.code shouldBe 200
      waitForEvent(Event.DEPLOYMENT_SUCCESS)
      result.value.version.toString
    }

    def restartSuccessfully(app: AppDefinition): VersionString = {
      val result = marathon.restartApp(app.id)
      result.code shouldBe 200
      waitForEvent(Event.DEPLOYMENT_SUCCESS)
      result.value.version.toString
    }

    def allTasks(appId: PathId): Iterable[ITEnrichedTask] = {
      Try(marathon.tasks(appId)).map(_.value).getOrElse(Nil)
    }

    def launchedTasks(appId: PathId): Iterable[ITEnrichedTask] = allTasks(appId).filter(_.launched)

    def suspendedTasks(appId: PathId): Iterable[ITEnrichedTask] = allTasks(appId).filter(_.suspended)
  }

  object Fixture {
    type VersionString = String

    object Event {
      val STATUS_UPDATE_EVENT = "status_update_event"
      val DEPLOYMENT_SUCCESS = "deployment_success"
    }

    object StatusUpdate {
      val TASK_FINISHED = "TASK_FINISHED"
      val TASK_RUNNING = "TASK_RUNNING"
      val TASK_FAILED = "TASK_FAILED"
    }

    /**
      * Resident Tasks reside in the TaskTracker even after they terminate and after the associated app is deleted.
      * To prevent spurious state in the above test cases, each test case should use a unique appId.
      */
    object IdGenerator {
      private[this] var index: Int = 0
      def generate(): String = {
        index += 1
        index.toString
      }
    }
  }

}
