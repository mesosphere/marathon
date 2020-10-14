package mesosphere.marathon
package storage.migration

import akka.stream.scaladsl.{Sink, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.state.AbsolutePathId
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json.{JsObject, JsString, Json}

class MigrationTo19200Test extends AkkaUnitTest {

  val mesosDefaultRole = "newDefaultRole"

  "Migration to 19.100" should {

    "only update instances with no role" in {

      Given("an instance with and one without role")
      val f = new Fixture()
      val instanceId1 = Instance.Id.forRunSpec(AbsolutePathId("/app"))
      val instanceId2 = Instance.Id.forRunSpec(AbsolutePathId("/app2"))

      val instances = Source(
        List(
          f.legacyInstanceJson(instanceId1),
          f.legacyInstanceJson(instanceId2) + ("role" -> JsString("someRole"))
        )
      )

      val migration = f.migration()

      When("they are run through the migration flow")
      val updatedInstances = instances.via(migration.instanceMigrationFlow).runWith(Sink.seq).futureValue

      Then("only one instance has been migrated")
      updatedInstances should have size (1)
      updatedInstances.head.instanceId should be(instanceId1)
      updatedInstances.head.role.value should be(mesosDefaultRole)
    }
  }

  class Fixture {

    val instanceRepository: InstanceRepository = mock[InstanceRepository]
    val persistenceStore: PersistenceStore[ZkId, String, ZkSerialized] = mock[PersistenceStore[ZkId, String, ZkSerialized]]

    def migration(): MigrationTo19200 = {

      new MigrationTo19200(mesosDefaultRole, instanceRepository, persistenceStore)
    }

    /**
      * Construct a 1.8.2 version JSON for an instance.
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
  }
}
