package mesosphere.marathon
package storage.migration

import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import mesosphere.marathon.state.{Instance, PathId}
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.Future

class InstanceMigrationTest extends AkkaUnitTest with StrictLogging {

  "Instance Migration" should {
    "save only found and updated instances" in {

      Given("two instances")
      val f = new Fixture()

      val instanceId1 = Id.forRunSpec(PathId("/app"))
      val instanceId2 = Id.forRunSpec(PathId("/app2"))
      val unknownInstanceId = Id.forRunSpec(PathId("/app3"))

      f.instanceRepository.ids() returns Source(List(instanceId1, instanceId2, unknownInstanceId))
      f.persistenceStore.get[Id, JsValue](equalTo(instanceId1))(any, any) returns Future(Some(f.legacyInstanceJson(instanceId1)))
      f.persistenceStore.get[Id, JsValue](equalTo(instanceId2))(any, any) returns Future(Some(f.legacyInstanceJson(instanceId2)))
      f.persistenceStore.get[Id, JsValue](equalTo(unknownInstanceId))(any, any) returns Future(None)
      f.instanceRepository.store(any) returns Future.successful(Done)

      When("they are migrated")
      val simpleFlow = Flow[JsValue].map(_.as[Instance](Instance.instanceJsonReads))
      InstanceMigration.migrateInstances(f.instanceRepository, f.persistenceStore, simpleFlow).futureValue

      Then("all updated instances are saved")
      verify(f.instanceRepository, times(2)).store(any)
    }
  }

  class Fixture {

    val instanceRepository: InstanceRepository = mock[InstanceRepository]
    val persistenceStore: ZkPersistenceStore = mock[ZkPersistenceStore]

    def legacyInstanceJson(i: Id): JsObject = Json.parse(
      s"""
         |{
         |  "instanceId": { "idString": "${i.idString}" },
         |  "tasksMap": {},
         |  "runSpecVersion": "2015-01-01T12:00:00.000Z",
         |  "agentInfo": { "host": "localhost", "attributes": [] },
         |  "state": { "since": "2015-01-01T12:00:00.000Z", "condition": { "str": "Running" }, "goal": "Running" }
         |}""".stripMargin).as[JsObject]
  }
}
