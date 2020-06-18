package mesosphere.marathon
package api.v2.json

import java.util.UUID

import mesosphere.UnitTest
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.appinfo.TaskCounts
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state._
import org.apache.mesos.{Protos => mesos}
import play.api.libs.json.{JsArray, JsObject, Json}

import scala.collection.immutable.Seq

class AppDefinitionAppInfoTest extends UnitTest {
  Formats.configureJacksonSerializer()

  val app = raml.App("/test", cmd = Some("sleep 123"), role = Some("*"))

  val counts = TaskCounts(
    tasksStaged = 3,
    tasksRunning = 5,
    tasksHealthy = 4,
    tasksUnhealthy = 1
  )

  val uuid = UUID.fromString("b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6")
  val taskId = Task.LegacyId(AbsolutePathId(app.id), ".", uuid)

  val readinessCheckResults = Seq(
    raml.TaskReadinessCheckResult("foo", taskId.idString, false, Some(raml.ReadinessCheckHttpResponse(503, "text/plain", "n/a")))
  )

  val deployments = Seq(
    raml.Identifiable("deployment1")
  )

  "AppDefinitionAppInfo" should {
    "app with taskCounts" in {
      Given("an app with counts")
      val extended = raml.AppInfo.fromParent(
        parent = app,
        tasksStaged = Some(3),
        tasksRunning = Some(5),
        tasksHealthy = Some(4),
        tasksUnhealthy = Some(1)
      )

      Then("the result contains all fields of the app plus the counts")
      val expectedJson = Json.toJson(app).as[JsObject] ++ Json.obj(
        "tasksStaged" -> 3,
        "tasksRunning" -> 5,
        "tasksHealthy" -> 4,
        "tasksUnhealthy" -> 1
      )
      JsonTestHelper.assertThatJsonOf(extended).correspondsToJsonOf(expectedJson)
      JsonTestHelper.assertThatJacksonJsonOf(extended).correspondsToJsonOf(expectedJson)
    }

    "app with deployments" in {
      Given("an app with deployments")
      val extended = raml.AppInfo.fromParent(parent = app, deployments = Some(deployments))

      Then("the result contains all fields of the app plus the deployments")
      val expectedJson = Json.toJson(app).as[JsObject] ++ Json.obj(
        "deployments" -> Seq(Json.obj("id" -> "deployment1"))
      )
      JsonTestHelper.assertThatJsonOf(extended).correspondsToJsonOf(expectedJson)
      JsonTestHelper.assertThatJacksonJsonOf(extended).correspondsToJsonOf(expectedJson)
    }

    "app with empty deployments list " in {
      Given("an app with empty deployments list")
      val extended = raml.AppInfo.fromParent(parent = app, deployments = Some(Seq.empty))

      Then("the result contains all fields of the app plus the deployments")
      val expectedJson = Json.toJson(app).as[JsObject] ++ Json.obj(
        "deployments" -> JsArray()
      )
      JsonTestHelper.assertThatJsonOf(extended).correspondsToJsonOf(expectedJson)
      JsonTestHelper.assertThatJacksonJsonOf(extended).correspondsToJsonOf(expectedJson)
    }

    "app with empty task list " in {
      Given("an app with empty task list")
      val extended = raml.AppInfo.fromParent(parent = app, tasks = Some(Seq.empty))

      Then("the result contains all fields of the app plus the tasks")
      val expectedJson = Json.toJson(app).as[JsObject] ++ Json.obj(
        "tasks" -> JsArray()
      )
      JsonTestHelper.assertThatJsonOf(extended).correspondsToJsonOf(expectedJson)
      JsonTestHelper.assertThatJacksonJsonOf(extended).correspondsToJsonOf(expectedJson)
    }

    "app with readiness results" in {
      Given("an app with deployments")
      val extended = raml.AppInfo.fromParent(app, readinessCheckResults = Some(readinessCheckResults))

      Then("the result contains all fields of the app plus the deployments")
      val expectedJson = Json.toJson(app).as[JsObject] ++ Json.obj(
        "readinessCheckResults" -> Seq(
          Json.obj(
            "name" -> "foo",
            "taskId" -> taskId.idString,
            "ready" -> false,
            "lastResponse" -> Json.obj(
              "status" -> 503,
              "contentType" -> "text/plain",
              "body" -> "n/a"
            )
          )
        )
      )
      JsonTestHelper.assertThatJsonOf(extended).correspondsToJsonOf(expectedJson)
      JsonTestHelper.assertThatJacksonJsonOf(extended).correspondsToJsonOf(expectedJson)
    }

    "app with taskCounts + deployments (show that combinations work)" in {
      Given("an app with counts")
      val extended = raml.AppInfo.fromParent(
        parent = app,
        tasksStaged = Some(3),
        tasksRunning = Some(5),
        tasksHealthy = Some(4),
        tasksUnhealthy = Some(1),
        deployments = Some(deployments)
      )

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
      JsonTestHelper.assertThatJacksonJsonOf(extended).correspondsToJsonOf(expectedJson)
    }

    "app with lastTaskFailure" in {
      Given("an app with a lastTaskFailure")
      val lastTaskFailure = raml.TaskFailure(
        appId = "/myapp",
        taskId = "myapp.2da6109e-4cce-11e5-98c1-be5b2935a987",
        state = mesos.TaskState.TASK_FAILED.toString,
        message = "Command exited with status 1",
        host = "srv2.dc43.mesosphere.com",
        timestamp = Timestamp("2015-08-27T15:13:48.386Z").toOffsetDateTime,
        version = Timestamp("2015-08-27T14:13:05.942Z").toOffsetDateTime,
        slaveId = Some("slave34")
      )
      val extended = raml.AppInfo.fromParent(parent = app, lastTaskFailure = Some(lastTaskFailure))

      Then("the result contains all fields of the app plus the deployments")
      val lastTaskFailureJson = Json.parse("""
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
      JsonTestHelper.assertThatJacksonJsonOf(extended).correspondsToJsonOf(expectedJson)
    }
  }
}
