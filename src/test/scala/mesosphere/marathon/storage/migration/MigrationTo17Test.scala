package mesosphere.marathon
package storage.migration

import java.util.Base64

import akka.Done
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.PathId
import mesosphere.marathon.storage.repository.InstanceRepository
import org.apache.mesos.{Protos => MesosProtos}
import org.scalatest.Inspectors
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.Future

class MigrationTo17Test extends AkkaUnitTest with StrictLogging with Inspectors {

  "Migration to 17" should {
    "save updated instances" in {

      Given("two ephemeral instances")
      val f = new Fixture()

      val instanceId1 = Instance.Id.forRunSpec(PathId("/app"))
      val instanceId2 = Instance.Id.forRunSpec(PathId("/app2"))

      f.instanceRepository.ids() returns Source(List(instanceId1, instanceId2))
      f.persistenceStore.get[Instance.Id, JsValue](equalTo(instanceId1))(any, any) returns Future(Some(f.legacyInstanceJson(instanceId1)))
      f.persistenceStore.get[Instance.Id, JsValue](equalTo(instanceId2))(any, any) returns Future(Some(f.legacyInstanceJson(instanceId2)))
      f.instanceRepository.store(any) returns Future.successful(Done)

      When("they are migrated")
      MigrationTo17.migrateInstanceGoals(f.instanceRepository, f.persistenceStore).futureValue

      Then("all updated instances are saved")
      verify(f.instanceRepository, times(2)).store(any)
    }

    "update only instances that are found" in {

      Given("two ephemeral instances and one that was not found")
      val f = new Fixture()
      val instanceId1 = Instance.Id.forRunSpec(PathId("/app"))
      val instanceId2 = Instance.Id.forRunSpec(PathId("/app2"))

      val instances = Source(List(Some(f.legacyInstanceJson(instanceId1)), None, Some(f.legacyInstanceJson(instanceId2))))

      When("they are run through the migration flow")
      val updatedInstances = instances.via(MigrationTo17.migrationFlow).runWith(Sink.seq).futureValue

      Then("only two instances have been migrated")
      updatedInstances should have size (2)
      forAll (updatedInstances) { i: Instance => i.state.goal should be(Goal.Running) }
    }

    "update terminal resident instances to stopped" in {

      Given("an ephemeral and a resident instance")
      val f = new Fixture()
      val instanceId1 = Instance.Id.forRunSpec(PathId("/app"))
      val instanceId2 = Instance.Id.forRunSpec(PathId("/app2"))

      val instances = Source(List(Some(f.legacyInstanceJson(instanceId1)), None, Some(f.legacyResidentInstanceJson(instanceId2))))

      When("they are run through the migration flow")
      println(f.legacyResidentInstanceJson(instanceId2))
      val updatedInstances = instances.via(MigrationTo17.migrationFlow).runWith(Sink.seq).futureValue

      Then("only two instances have been migrated")
      updatedInstances should have size (2)
      updatedInstances.map(_.state.goal) should contain theSameElementsAs List(Goal.Running, Goal.Stopped)
    }
  }

  class Fixture {

    val instanceRepository: InstanceRepository = mock[InstanceRepository]
    val persistenceStore: ZkPersistenceStore = mock[ZkPersistenceStore]

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
      * @param i The id of the instance.
      * @return The JSON of the instance.
      */
    def legacyResidentInstanceJson(i: Instance.Id): JsValue = {
      val taskId = Task.Id.forInstanceId(i, None)

      legacyInstanceJson(i) ++
        Json.obj("state" -> Json.obj("since" -> "2015-01-01T12:00:00.000Z", "condition" -> Json.obj("str" -> "Reserved"), "goal" -> "running")) ++
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
