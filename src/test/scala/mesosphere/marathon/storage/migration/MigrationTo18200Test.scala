package mesosphere.marathon
package storage.migration

import java.util.Base64

import akka.stream.scaladsl.{Sink, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.{Instance, Reservation}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AbsolutePathId
import org.apache.mesos.{Protos => MesosProtos}
import play.api.libs.json.{JsObject, JsString, JsValue, Json}

class MigrationTo18200Test extends AkkaUnitTest {

  "Migration to 18.200" should {

    "only update instances with reservation but without reservation id" in {

      Given("an ephemeral and two resident instances")
      val f = new Fixture()
      val instanceId1 = Instance.Id.forRunSpec(AbsolutePathId("/app"))
      val instanceId2 = Instance.Id.forRunSpec(AbsolutePathId("/app2"))
      val instanceId3 = Instance.Id.forRunSpec(AbsolutePathId("/app3"))

      val taskId = Task.Id(instanceId2)
      val instances = Source(
        List(
          f.legacyInstanceJson(instanceId1),
          f.legacyResidentInstanceJson(instanceId2, taskId.idString, f.task(taskId, MesosProtos.TaskState.TASK_RUNNING), None),
          f.legacyResidentInstanceJson(
            instanceId3,
            taskId.idString,
            f.task(taskId, MesosProtos.TaskState.TASK_RUNNING),
            Some("reservation-id")
          )
        )
      )

      When("they are run through the migration flow")
      val updatedInstances = instances.via(MigrationTo18200.migrationFlow).runWith(Sink.seq).futureValue

      Then("only one instance has been migrated")
      updatedInstances should have size (1)
      updatedInstances.head.instanceId should be(instanceId2)
      updatedInstances.head.reservation.value.id should be(Reservation.SimplifiedId(instanceId2))
    }
  }

  class Fixture {

    /**
      * Construct a 1.8.0 version JSON for an instance.
      * @param i The id of the instance.
      * @return The JSON of the instance.
      */
    def legacyInstanceJson(i: Instance.Id): JsObject = Json.parse(s"""
         |{
         |  "instanceId": { "idString": "${i.idString}" },
         |  "tasksMap": {},
         |  "runSpecVersion": "2015-01-01T12:00:00.000Z",
         |  "agentInfo": { "host": "localhost", "attributes": [] },
         |  "state": { "since": "2015-01-01T12:00:00.000Z", "condition": { "str": "Running" }, "goal": "Running" }
         |}""".stripMargin).as[JsObject]

    /**
      * Construct a 1.8.0 version JSON for a terminal resident instance.
      * @param id The id of the instance.
      * @return The JSON of the instance.
      */
    def legacyResidentInstanceJson(id: Instance.Id, taskId: String, task: JsValue, reservationId: Option[String]): JsValue = {

      val maybReservationJsObject = JsObject(reservationId.toSeq.map("id" -> JsString(_)))
      val reservation = Json.obj(
        "reservation" -> (
          Json.obj("volumeIds" -> Json.arr(), "state" -> Json.obj("name" -> "suspended")) ++ maybReservationJsObject
        )
      )

      legacyInstanceJson(id) ++
        Json.obj(
          "state" -> Json.obj("since" -> "2015-01-01T12:00:00.000Z", "condition" -> Json.obj("str" -> "Reserved"), "goal" -> "Running")
        ) ++
        reservation ++
        Json.obj("tasksMap" -> Json.obj(taskId -> task))
    }

    /**
      * Construct a task in the 1.8.0 JSON format.
      * @param taskId The id of the task.
      * @param state The Mesos state of the task.
      * @return The JSON object of the task.
      */
    def task(taskId: Task.Id, state: MesosProtos.TaskState): JsValue = task(taskId, taskStatus(taskId.idString, state))
    def task(taskId: Task.Id, status: JsValue): JsValue =
      Json.obj(
        "taskId" -> taskId.idString,
        "runSpecVersion" -> "2015-01-01T12:00:00.000Z",
        "status" -> status
      )

    /**
      * Construct a task status in 1.8.0 JSON format.
      * @param taskId The if of the task their status belongs to.
      * @param state Mesos state of the task
      * @return The JSON object of the task status.
      */
    def taskStatus(taskId: String, state: MesosProtos.TaskState): JsValue = {
      val mesosTaskStatus: MesosProtos.TaskStatus = MesosProtos.TaskStatus
        .newBuilder()
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
