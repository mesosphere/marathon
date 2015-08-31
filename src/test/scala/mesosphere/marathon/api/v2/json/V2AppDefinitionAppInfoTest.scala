package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.appinfo.{ AppInfo, TaskCounts }
import mesosphere.marathon.state.{ AppDefinition, Timestamp, TaskFailure, Identifiable, PathId }
import org.scalatest.GivenWhenThen
import play.api.libs.json.{ Writes, JsObject, Json }
import scala.collection.immutable.Seq
import org.apache.mesos.{ Protos => mesos }

class V2AppDefinitionAppInfoTest extends MarathonSpec with GivenWhenThen {
  import Formats._

  implicit val appDefinitionWrites: Writes[AppDefinition] =
    Writes(app => V2AppDefinitionWrites.writes(V2AppDefinition(app)))

  val app = AppDefinition(PathId("/test"), cmd = Some("sleep 123"))

  val counts = TaskCounts(
    tasksStaged = 3,
    tasksRunning = 5,
    tasksHealthy = 4,
    tasksUnhealthy = 1
  )

  val deployments = Seq(
    Identifiable("deployment1")
  )

  test("app with taskCounts") {
    Given("an app with counts")
    val extended = AppInfo(app, maybeCounts = Some(counts))

    Then("the result contains all fields of the app plus the counts")
    val expectedJson = Json.toJson(app).as[JsObject] ++ Json.obj(
      "tasksStaged" -> 3,
      "tasksRunning" -> 5,
      "tasksHealthy" -> 4,
      "tasksUnhealthy" -> 1
    )
    JsonTestHelper.assertThatJsonOf(extended).correspondsToJsonOf(expectedJson)
  }

  test("app with deployments") {
    Given("an app with deployments")
    val extended = AppInfo(app, maybeDeployments = Some(deployments))

    Then("the result contains all fields of the app plus the deployments")
    val expectedJson = Json.toJson(app).as[JsObject] ++ Json.obj(
      "deployments" -> Seq(Json.obj("id" -> "deployment1"))
    )
    JsonTestHelper.assertThatJsonOf(extended).correspondsToJsonOf(expectedJson)
  }

  test("app with taskCounts + deployments (show that combinations work)") {
    Given("an app with counts")
    val extended = AppInfo(app, maybeCounts = Some(counts), maybeDeployments = Some(deployments))

    Then("the result contains all fields of the app plus the counts")
    val expectedJson =
      Json.toJson(app).as[JsObject] ++
        Json.obj(
          "tasksStaged" -> 3,
          "tasksRunning" -> 5,
          "tasksHealthy" -> 4,
          "tasksUnhealthy" -> 1
        ) ++ Json.obj(
            "deployments" -> Seq(Json.obj("id" -> "deployment1"))
          )
    JsonTestHelper.assertThatJsonOf(extended).correspondsToJsonOf(expectedJson)
  }

  test("app with lastTaskFailure") {
    Given("an app with a lastTaskFailure")
    val lastTaskFailure = new TaskFailure(
      appId = PathId("/myapp"),
      taskId = mesos.TaskID.newBuilder().setValue("myapp.2da6109e-4cce-11e5-98c1-be5b2935a987").build(),
      state = mesos.TaskState.TASK_FAILED,
      message = "Command exited with status 1",
      host = "srv2.dc43.mesosphere.com",
      timestamp = Timestamp("2015-08-27T15:13:48.386Z"),
      version = Timestamp("2015-08-27T14:13:05.942Z"),
      slaveId = Some(mesos.SlaveID.newBuilder().setValue("slave34").build())
    )
    val extended = AppInfo(app, maybeLastTaskFailure = Some(lastTaskFailure))

    Then("the result contains all fields of the app plus the deployments")
    val lastTaskFailureJson = Json.parse(
      """
       | {
       |   "lastTaskFailure": {
       |     "appId": "/myapp",
       |     "host": "srv2.dc43.mesosphere.com",
       |     "message": "Command exited with status 1",
       |     "state": "TASK_FAILED",
       |     "taskId": "myapp.2da6109e-4cce-11e5-98c1-be5b2935a987",
       |     "slaveId": "slave34",
       |     "timestamp": "2015-08-27T15:13:48.386Z",
       |     "version": "2015-08-27T14:13:05.942Z"
       |   }
       | }
       |""".stripMargin('|')).as[JsObject]
    val expectedJson = Json.toJson(app).as[JsObject] ++ lastTaskFailureJson
    JsonTestHelper.assertThatJsonOf(extended).correspondsToJsonOf(expectedJson)
  }
}
