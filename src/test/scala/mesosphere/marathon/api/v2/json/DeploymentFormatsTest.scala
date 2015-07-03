package mesosphere.marathon.api.v2.json

import java.util.UUID

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, Group, Timestamp }
import mesosphere.marathon.upgrade._
import org.scalatest.Matchers._
import play.api.libs.json._
import scala.collection.immutable.Seq

import scala.util.Random

class DeploymentFormatsTest extends MarathonSpec {
  import Formats._

  test("Can read V2GroupUpdate json") {
    val json = """
      |{
      |  "id": "a",
      |  "apps": [{ "id": "b", "version": "2015-06-03T13:00:52.883Z" }],
      |  "groups":[ { "id": "c", "version": "2015-06-03T13:00:52.928Z"}],
      |  "dependencies": [ "d" ],
      |  "scaleBy": 23.0,
      |  "version": "2015-06-03T13:00:52.928Z"
      |}
      |""".stripMargin
    val update = Json.parse(json).as[V2GroupUpdate]
    update.id should be(Some("a".toPath))
    update.apps should be ('defined)
    update.apps.get should have size 1
    update.apps.get.head.id should be("b".toPath)
    update.groups should be ('defined)
    update.groups.get should have size 1
    update.groups.get.head.id should be(Some("c".toPath))
    update.dependencies should be('defined)
    update.dependencies.get.head should be("d".toPath)
    update.scaleBy should be('defined)
    update.scaleBy.get should be(23.0 +- 0.01)
    update.version should be('defined)
    update.version.get should be(Timestamp("2015-06-03T13:00:52.928Z"))
  }

  test("Can write/read V2GroupUpdate") {
    marshalUnmarshal(genGroupUpdate())
    marshalUnmarshal(genGroupUpdate(Set(genGroupUpdate(), genGroupUpdate(Set(genGroupUpdate())))))
  }

  test("Will read from no given value") {
    val groupFromNull = JsNull.as[V2GroupUpdate]
    groupFromNull.id should be('empty)
    groupFromNull.apps should be('empty)
    groupFromNull.groups should be('empty)
    groupFromNull.dependencies should be('empty)
    groupFromNull.scaleBy should be('empty)
    groupFromNull.version should be('empty)
  }

  test("Can read Group json") {
    val json =
      """
        |{
        |  "id": "a",
        |  "apps": [
        |    { "id": "b", "version": "2015-06-03T13:18:25.639Z" }
        |  ],
        |  "groups": [{
        |    "id": "c",
        |    "version": "2015-06-03T13:18:26.642Z"
        |  }],
        |  "dependencies": [ "d" ],
        |  "version": "2015-06-03T13:18:25.640Z"
        |}
      """.stripMargin
    val group = Json.parse(json).as[V2Group]
    group.id should be("a".toPath)
    group.apps should have size 1
    group.apps.head.id should be("b".toPath)
    group.groups should have size 1
    group.groups.head.id should be("c".toPath)
    group.dependencies.head should be("d".toPath)
    group.version should be(Timestamp("2015-06-03T13:18:25.640Z"))
  }

  test("Can write/read V2Group") {
    marshalUnmarshal(genGroup())
    marshalUnmarshal(genGroup(Set(genGroup(), genGroup(Set(genGroup())))))
  }

  test("Can write/read byte arrays") {
    marshalUnmarshal("Hello".getBytes)
  }

  test("DeploymentPlan can be serialized") {
    val plan = DeploymentPlan(
      genId.toString,
      genGroup().toGroup,
      genGroup(Set(genGroup(), genGroup())).toGroup,
      Seq(genStep),
      Timestamp.now()
    )
    val json = Json.toJson(plan)
    val fieldMap = json.as[JsObject].fields.toMap
    fieldMap.keySet should be(Set("version", "id", "target", "original", "steps"))
  }

  // regression test for #1176
  test("allow / as id") {
    val json = """{"id": "/"}"""
    assert(Json.parse(json).as[V2Group].id.isRoot)
  }

  def marshalUnmarshal[T](original: T)(implicit format: Format[T]): JsValue = {
    val json = Json.toJson(original)
    val read = json.as[T]
    read should be (original)
    json
  }

  def genInt = Random.nextInt(1000)

  def genId = UUID.randomUUID().toString.toPath

  def genTimestamp = Timestamp.now()

  def genApp = V2AppDefinition(id = genId)

  def genStep = DeploymentStep(actions = Seq(
    StartApplication(genApp.toAppDefinition, genInt),
    ScaleApplication(genApp.toAppDefinition, genInt),
    StopApplication(genApp.toAppDefinition),
    RestartApplication(genApp.toAppDefinition),
    ResolveArtifacts(genApp.toAppDefinition, Map.empty)
  ))

  def genGroup(children: Set[V2Group] = Set.empty) =
    V2Group(genId, Set(genApp, genApp), children, Set(genId), genTimestamp)

  def genGroupUpdate(children: Set[V2GroupUpdate] = Set.empty) =
    V2GroupUpdate(
      Some(genId),
      Some(Set(genApp, genApp)),
      Some(children),
      Some(Set(genId)),
      Some(23),
      Some(genTimestamp)
    )

}
