package mesosphere.marathon.tasks

import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.tasks.IterativeOfferMatcher.{ OfferUsage, OfferUsages }
import mesosphere.marathon.{ MarathonConf, MarathonTestHelper }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import org.apache.mesos.Protos.{ Offer, OfferID, TaskInfo }
import org.apache.mesos.SchedulerDriver
import org.apache.mesos.state.{ State, InMemoryState }
import org.mockito.{ ArgumentCaptor, Mockito }
import org.scalatest.{ BeforeAndAfter, ShouldMatchers, FunSuite, GivenWhenThen }
import scala.collection.JavaConverters._

import scala.collection.immutable.Seq

class IterativeOfferMatcherTest extends FunSuite with GivenWhenThen with ShouldMatchers {

  var config: MarathonConf = _
  var taskQueue: TaskQueue = _
  var state: State = _
  var metricRegistry: MetricRegistry = _
  var taskTracker: TaskTracker = _
  var taskFactory: TaskFactory = _
  var metrics: IterativeOfferMatcherMetrics = _
  var matcher: IterativeOfferMatcher = _

  def createEnv(maxTasksPerOffer: Int, maxTasksPerOfferCycle: Int = 1000): Unit = {
    config = MarathonTestHelper.defaultConfig(
      maxTasksPerOffer = maxTasksPerOffer, maxTasksPerOfferCycle = maxTasksPerOfferCycle)
    taskQueue = new TaskQueue
    state = new InMemoryState
    metricRegistry = new MetricRegistry
    taskTracker = new TaskTracker(state, config, metricRegistry)
    taskFactory = new DefaultTaskFactory(TaskIdUtil, taskTracker, config, new ObjectMapper())
    metrics = new IterativeOfferMatcherMetrics(metricRegistry)
    matcher = new IterativeOfferMatcher(config, taskQueue, taskTracker, taskFactory, metrics)
  }

  val now = Timestamp.now()
  val app = AppDefinition(
    id = "testOffers".toRootPath,
    executor = "//cmd",
    ports = Seq(8080),
    version = now
  )

  test("Calculate offer usages for one task") {
    Given("a in-memory matcher")
    createEnv(maxTasksPerOffer = 1)

    Given("one app definition with one requested task")
    taskQueue.add(app)

    When("calculating usages")
    val offers = Seq(MarathonTestHelper.makeBasicOffer().build())
    val usages = matcher.calculateOfferUsage(offers)

    Then("the offer gets used for one task")
    usages.usages should have size (1)
    usages.usages.head.scheduledTasks should have size (1)
  }

  test("Calculate offer usages for three tasks and three offers") {
    Given("a in-memory matcher")
    createEnv(maxTasksPerOffer = 1)

    Given("one app definition with three requested tasks")
    taskQueue.add(app, 3)

    When("calculating usages for three offers")
    val offers = (1 to 3).map(_ => MarathonTestHelper.makeBasicOffer().build())
    val usages = matcher.calculateOfferUsage(offers)

    Then("each offer gets used for one task")
    usages.usages should have size (3)
    for ((usage, idx) <- usages.usages.zipWithIndex) {
      withClue(s"at index $idx, offer ${usage.remainingOffer.getId.getValue}") {
        usage.scheduledTasks should have size (1)
      }
    }
  }

  test("Calculate offer usages for three tasks and three offers, overall task limit 2") {
    Given("a in-memory matcher with an overall task limit of 2")
    createEnv(maxTasksPerOffer = 1, maxTasksPerOfferCycle = 2)

    Given("one app definition with three requested tasks")
    taskQueue.add(app, 3)

    When("calculating usages for three offers")
    val offers = (1 to 3).map(_ => MarathonTestHelper.makeBasicOffer().build())
    val usages = matcher.calculateOfferUsage(offers)

    Then("the offers get used for two tasks (== maxTasksPerOfferCycle)")
    usages.usages should have size (3)
    for ((usage, idx) <- usages.usages.take(2).zipWithIndex) {
      withClue(s"at index $idx, offer ${usage.remainingOffer.getId.getValue}") {
        usage.scheduledTasks should have size (1)
      }
    }
    usages.usages.last.scheduledTasks should have size (0)
  }

  test("Calculate offer usages for ten tasks") {
    Given("a in-memory matcher")
    createEnv(maxTasksPerOffer = 10)

    Given("one app definition with ten requested tasks")
    taskQueue.add(app, 10)

    When("calculating usages for one offer")
    val offers = Seq(MarathonTestHelper.makeBasicOffer(cpus = 10).build())
    val usages = matcher.calculateOfferUsage(offers)

    Then("the one offer gets used for ten tasks (== maxTasksPerOffer)")
    usages.usages should have size (1)
    usages.usages.head.scheduledTasks should have size (10)
  }

  test("Calculate offer usages for ten tasks, with overall cycle limit 9") {
    Given("a in-memory matcher")
    createEnv(maxTasksPerOffer = 10, maxTasksPerOfferCycle = 9)

    Given("one app definition with ten requested tasks")
    taskQueue.add(app, 10)

    When("calculating usages")
    val offers = Seq(MarathonTestHelper.makeBasicOffer(cpus = 10).build())
    val usages = matcher.calculateOfferUsage(offers)

    Then("the one offer gets used for nine tasks")
    usages.usages should have size (1)
    usages.usages.head.scheduledTasks should have size (9)
  }

  test("Calculate offer usages for 11 tasks, leave one unscheduled") {
    Given("a in-memory matcher")
    createEnv(maxTasksPerOffer = 10)

    Given("one app definition with 11 requested tasks")
    taskQueue.add(app, 11)

    When("calculating usages")
    val offers = Seq(MarathonTestHelper.makeBasicOffer(cpus = 10).build())
    val usages = matcher.calculateOfferUsage(offers)

    Then("the one offer gets used for ten tasks (== maxTasksPerOffer)")
    usages.usages should have size (1)
    usages.usages.head.scheduledTasks should have size (10)
  }

  test("Committing decline to driver") {
    Given("a in-memory matcher")
    createEnv(maxTasksPerOffer = 10)
    val driver = Mockito.mock(classOf[SchedulerDriver], "schedulerDriver")

    Given("one unused offer")
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 10).build()
    val usages = OfferUsages(
      depleted = Vector(
        OfferUsage(
          offer,
          Vector()
        )
      )
    )

    When("committing usages")
    matcher.commitOfferUsagesToDriver(driver, usages)

    Then("expect a declineOffer call")
    Mockito.verify(driver, Mockito.times(1)).declineOffer(offer.getId)
    Mockito.verifyNoMoreInteractions(driver)
  }

  test("Committing launch tasks to driver") {
    Given("a in-memory matcher")
    createEnv(maxTasksPerOffer = 10)
    val driver = Mockito.mock(classOf[SchedulerDriver], "schedulerDriver")

    Given("one unused offer")
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 10).build()
    val taskInfo: TaskInfo = taskFactory.newTask(app, offer).get._1
    val usages = OfferUsages(
      depleted = Vector(
        OfferUsage(
          offer,
          Vector(taskInfo)
        )
      )
    )

    When("committing usages")
    matcher.commitOfferUsagesToDriver(driver, usages)

    Then("expect a launchTasks call")
    val offersCaptor = ArgumentCaptor.forClass(classOf[java.util.Collection[OfferID]])
    val taskInfosCaptor = ArgumentCaptor.forClass(classOf[java.util.Collection[TaskInfo]])
    Mockito.verify(driver, Mockito.times(1)).launchTasks(offersCaptor.capture(), taskInfosCaptor.capture())
    Mockito.verifyNoMoreInteractions(driver)

    offersCaptor.getValue.asScala.toSeq should be(Seq(offer.getId))
    taskInfosCaptor.getValue.asScala.toSeq should be(Seq(taskInfo))
  }

  test("Committing launch tasks/ decline offers to driver") {
    Given("a in-memory matcher")
    createEnv(maxTasksPerOffer = 10)
    val driver = Mockito.mock(classOf[SchedulerDriver], "schedulerDriver")

    Given("one unused offer")
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 10).build()
    val offer2: Offer = offer.toBuilder.setId(OfferID.newBuilder().setValue("123")).build()
    val taskInfo: TaskInfo = taskFactory.newTask(app, offer).get._1
    val usages = OfferUsages(
      depleted = Vector(
        OfferUsage(
          offer,
          Vector(taskInfo)
        ),
        OfferUsage(offer2)
      )
    )

    When("committing usages")
    matcher.commitOfferUsagesToDriver(driver, usages)

    Then("expect a launchTasks call")
    val launchTasksOffersCaptor = ArgumentCaptor.forClass(classOf[java.util.Collection[OfferID]])
    val taskInfosCaptor = ArgumentCaptor.forClass(classOf[java.util.Collection[TaskInfo]])
    Mockito.verify(driver, Mockito.times(1)).launchTasks(launchTasksOffersCaptor.capture(), taskInfosCaptor.capture())
    Mockito.verify(driver, Mockito.times(1)).declineOffer(offer2.getId)
    Mockito.verifyNoMoreInteractions(driver)

    launchTasksOffersCaptor.getValue.asScala.toSeq should be(Seq(offer.getId))
    taskInfosCaptor.getValue.asScala.toSeq should be(Seq(taskInfo))
  }
}
