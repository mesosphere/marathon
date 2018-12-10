package mesosphere.marathon
package storage.migration

import java.util.Base64

import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import org.apache.mesos.{Protos => MesosProtos}
import org.scalatest.Inspectors
import play.api.libs.json.{JsObject, JsValue, Json}

class MigrationTo17Test extends AkkaUnitTest with StrictLogging with Inspectors {

  "Migration to 17" should {
    "update only instances that are found" in {

      Given("two ephemeral instances and one that was not found")
      val f = new Fixture()
      val instanceId1 = Instance.Id.forRunSpec(PathId("/app"))
      val instanceId2 = Instance.Id.forRunSpec(PathId("/app2"))

      val instances = Source(List(f.legacyInstanceJson(instanceId1), f.legacyInstanceJson(instanceId2)))

      When("they are run through the migration flow")
      val updatedInstances = instances.via(MigrationTo17.migrationFlow).runWith(Sink.seq).futureValue

      Then("only two instances have been migrated")
      updatedInstances should have size (2)
      forAll (updatedInstances) { i: state.Instance => i.state.goal should be(Goal.Running) }
    }

    "update terminal resident instances to stopped" in {

      Given("an ephemeral and a resident instance")
      val f = new Fixture()
      val instanceId1 = Instance.Id.forRunSpec(PathId("/app"))
      val instanceId2 = Instance.Id.forRunSpec(PathId("/app2"))

      val instances = Source(List(f.legacyInstanceJson(instanceId1), f.legacyResidentInstanceJson(instanceId2)))

      When("they are run through the migration flow")
      val updatedInstances = instances.via(MigrationTo17.migrationFlow).runWith(Sink.seq).futureValue

      Then("only two instances have been migrated")
      updatedInstances should have size (2)
      updatedInstances.map(_.state.goal) should contain theSameElementsAs List(Goal.Running, Goal.Stopped)
    }
  }

  class Fixture {

    /**
      * Construct a 1.6.0 version JSON for an instance.
      * @param i The id of the instance.
      * @return The JSON of the instance.
      */
    def legacyInstanceJson(i: Instance.Id): JsObject = Json.parse(
      s"""
        |{
        |  "instanceId": { "idString": "${i.idString}" },
        |  "tasksMap": {},
        |  "runSpecVersion": "2015-01-01T12:00:00.000Z",
        |  "agentInfo": { "host": "localhost", "attributes": [] },
        |  "state": { "since": "2015-01-01T12:00:00.000Z", "condition": { "str": "Running" }, "goal": "running" }
        |}""".stripMargin).as[JsObject]

    /**
      * Construct a 1.6.0 version JSON for a terminal resident instance.
      * @param id The id of the instance.
      * @return The JSON of the instance.
      */
    def legacyResidentInstanceJson(id: Instance.Id): JsValue = {
      val taskId = Task.Id(id)

      legacyInstanceJson(id) ++
        Json.obj("state" -> Json.obj("since" -> "2015-01-01T12:00:00.000Z", "condition" -> Json.obj("str" -> "Killed"), "goal" -> "running")) ++
        Json.obj("reservation" -> Json.obj("volumeIds" -> Json.arr(), "state" -> Json.obj("name" -> "suspended"))) ++
        Json.obj("tasksMap" -> Json.obj(taskId.idString -> terminalTask(taskId)))
    }

    /**
      * Construct a terminal task in the 1.6.0 JSON format.
      * @param taskId The id of the terminal task.
      * @return The JSON object of the task.
      */
    def terminalTask(taskId: Task.Id): JsValue = Json.obj(
      "taskId" -> taskId.idString,
      "runSpecVersion" -> "2015-01-01T12:00:00.000Z",
      "status" -> terminalTaskStatus(taskId.idString)
    )

    /**
      * Construct a terminal task status in 1.6.0 JSON format.
      * @param taskId The if of the task ther status belongs to.
      * @return The JSON object of the terminal task status.
      */
    def terminalTaskStatus(taskId: String): JsValue = {
      val state = MesosProtos.TaskState.TASK_FINISHED
      val mesosTaskStatus: MesosProtos.TaskStatus = MesosProtos.TaskStatus.newBuilder()
        .setState(state)
        .setTaskId(MesosProtos.TaskID.newBuilder().setValue(taskId).build())
        .build()

      Json.obj(
        "stagedAt" -> "2015-01-01T12:00:00.000Z",
        "condition" -> "Reserved",
        "mesosStatus" -> Base64.getEncoder.encodeToString(mesosTaskStatus.toByteArray),
        "networkInfo" -> Json.obj("hostName" -> "localhost", "hostPorts" -> Json.arr(), "ipAddresses" -> Json.arr())
      )
    }
  }
}
