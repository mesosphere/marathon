package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.{ AppDefinition, UpgradeStrategy }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.Timestamp

import org.scalatest.Matchers
import play.api.libs.json._

class AppDefinitionFormatsTest extends MarathonSpec with AppDefinitionFormats with Matchers {

  object Fixture {
    val a1 = AppDefinition(
      id = "app1".toPath,
      cmd = Some("sleep 10"),
      version = Timestamp(1)
    )

    val j1 = Json.parse("""
      {
        "id": "app1",
        "cmd": "sleep 10",
        "version": "1970-01-01T00:00:00.001Z"
      }
    """)
  }

  test("ToJson") {
    import Fixture._
    import AppDefinition._

    val r1 = Json.toJson(a1)
    // check supplied values
    r1 \ "id" should equal (JsString("app1"))
    r1 \ "cmd" should equal (JsString("sleep 10"))
    r1 \ "version" should equal (JsString("1970-01-01T00:00:00.001Z"))
    // check default values
    r1 \ "args" should equal (JsNull)
    r1 \ "user" should equal (JsNull)
    r1 \ "env" should equal (JsObject(Seq.empty))
    r1 \ "instances" should equal (JsNumber(DefaultInstances))
    r1 \ "cpus" should equal (JsNumber(DefaultCpus))
    r1 \ "mem" should equal (JsNumber(DefaultMem))
    r1 \ "disk" should equal (JsNumber(DefaultDisk))
    r1 \ "executor" should equal (JsString(""))
    r1 \ "constraints" should equal (JsArray(Seq.empty))
    r1 \ "uris" should equal (JsArray(Seq.empty))
    r1 \ "storeUrls" should equal (JsArray(Seq.empty))
    r1 \ "ports" should equal (JsArray(DefaultPorts.map { p => JsNumber(p.toInt) }))
    r1 \ "requirePorts" should equal (JsBoolean(DefaultRequirePorts))
    r1 \ "backoffSeconds" should equal (JsNumber(DefaultBackoff.toSeconds))
    r1 \ "backoffFactor" should equal (JsNumber(DefaultBackoffFactor))
    r1 \ "container" should equal (JsNull)
    r1 \ "healthChecks" should equal (JsArray(Seq.empty))
    r1 \ "dependencies" should equal (JsArray(Seq.empty))
    r1 \ "upgradeStrategy" should equal (Json.toJson(UpgradeStrategy.empty))
  }

  test("FromJson") {
    import Fixture._
    import AppDefinition._

    val r1 = j1.as[AppDefinition]
    // check supplied values
    r1.id should equal (a1.id)
    r1.cmd should equal (a1.cmd)
    r1.version should equal (Timestamp(1))
    // check default values
    r1.args should equal (None)
    r1.user should equal (None)
    r1.env should equal (Map.empty)
    r1.instances should equal (DefaultInstances)
    r1.cpus should equal (DefaultCpus)
    r1.mem should equal (DefaultMem)
    r1.disk should equal (DefaultDisk)
    r1.executor should equal ("")
    r1.constraints should equal (Set.empty)
    r1.uris should equal (Seq.empty)
    r1.storeUrls should equal (Seq.empty)
    r1.ports should equal (DefaultPorts)
    r1.requirePorts should equal (DefaultRequirePorts)
    r1.backoff should equal (DefaultBackoff)
    r1.backoffFactor should equal (DefaultBackoffFactor)
    r1.container should equal (None)
    r1.healthChecks should equal (Set.empty)
    r1.dependencies should equal (Set.empty)
    r1.upgradeStrategy should equal (UpgradeStrategy.empty)
  }

}

