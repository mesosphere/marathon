package mesosphere.marathon.core.task.tracker.impl

import akka.stream.scaladsl.Source
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.PathId
import mesosphere.marathon.storage.repository.TaskRepository
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.concurrent.Future

class TaskLoaderImplTest
    extends FunSuite with MarathonSpec with Mockito with GivenWhenThen
    with ScalaFutures with Matchers with MarathonActorSupport {
  test("loading no tasks") {
    val f = new Fixture

    Given("no tasks")
    f.taskRepository.ids() returns Source.empty

    When("loadTasks is called")
    val loaded = f.loader.loadTasks()

    Then("taskRepository.ids gets called")
    verify(f.taskRepository).ids()

    And("our data is empty")
    loaded.futureValue.allTasks should be(empty)

    And("there are no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("loading multiple tasks for multiple apps") {
    val f = new Fixture

    Given("tasks for multiple apps")
    val app1Id = PathId("/app1")
    val app1task1 = MarathonTestHelper.mininimalTask(app1Id)
    val app1task2 = MarathonTestHelper.mininimalTask(app1Id)
    val app2Id = PathId("/app2")
    val app2task1 = MarathonTestHelper.mininimalTask(app2Id)
    val tasks = Iterable(app1task1, app1task2, app2task1)

    f.taskRepository.ids() returns Source(tasks.map(_.taskId)(collection.breakOut))
    for (task <- tasks) {
      f.taskRepository.get(task.taskId) returns Future.successful(Some(task))
    }

    When("loadTasks is called")
    val loaded = f.loader.loadTasks()

    Then("the resulting data is correct")
    // we do not need to verify the mocked calls because the only way to get the data is to perform the calls
    val appData1 = TaskTracker.AppTasks.forTasks(app1Id, Iterable(app1task1, app1task2))
    val appData2 = TaskTracker.AppTasks.forTasks(app2Id, Iterable(app2task1))
    val expectedData = TaskTracker.TasksByApp.of(appData1, appData2)
    loaded.futureValue should equal(expectedData)
  }

  class Fixture {
    lazy val taskRepository = mock[TaskRepository]
    lazy val loader = new TaskLoaderImpl(taskRepository)

    def verifyNoMoreInteractions(): Unit = noMoreInteractions(taskRepository)
  }
}
