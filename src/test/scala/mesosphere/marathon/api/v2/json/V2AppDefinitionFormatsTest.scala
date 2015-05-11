package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.{ AppDefinition, PathId, UpgradeStrategy, Timestamp }
import mesosphere.marathon.state.PathId._

import org.scalatest.Matchers
import play.api.libs.json._

class V2AppDefinitionFormatsTest
    extends MarathonSpec
    with V2Formats
    with HealthCheckFormats
    with Matchers {

  import Formats.PathIdFormat

  object Fixture {
    val a1 = V2AppDefinition(
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
    r1 \ "env" should equal (JsObject(DefaultEnv.mapValues(JsString(_)).toSeq))
    r1 \ "instances" should equal (JsNumber(DefaultInstances))
    r1 \ "cpus" should equal (JsNumber(DefaultCpus))
    r1 \ "mem" should equal (JsNumber(DefaultMem))
    r1 \ "disk" should equal (JsNumber(DefaultDisk))
    r1 \ "executor" should equal (JsString(DefaultExecutor))
    r1 \ "constraints" should equal (Json.toJson(DefaultConstraints))
    r1 \ "uris" should equal (Json.toJson(DefaultUris))
    r1 \ "storeUrls" should equal (Json.toJson(DefaultStoreUrls))
    r1 \ "ports" should equal (JsArray(DefaultPorts.map { p => JsNumber(p.toInt) }))
    r1 \ "requirePorts" should equal (JsBoolean(DefaultRequirePorts))
    r1 \ "backoffSeconds" should equal (JsNumber(DefaultBackoff.toSeconds))
    r1 \ "backoffFactor" should equal (JsNumber(DefaultBackoffFactor))
    r1 \ "maxLaunchDelaySeconds" should equal (JsNumber(DefaultMaxLaunchDelay.toSeconds))
    r1 \ "container" should equal (JsNull)
    r1 \ "healthChecks" should equal (Json.toJson(DefaultHealthChecks))
    r1 \ "dependencies" should equal (Json.toJson(DefaultDependencies))
    r1 \ "upgradeStrategy" should equal (Json.toJson(DefaultUpgradeStrategy))
  }

  test("FromJson") {
    import Fixture._
    import AppDefinition._

    val r1 = j1.as[V2AppDefinition]
    // check supplied values
    r1.id should equal (a1.id)
    r1.cmd should equal (a1.cmd)
    r1.version should equal (Timestamp(1))
    // check default values
    r1.args should equal (DefaultArgs)
    r1.user should equal (DefaultUser)
    r1.env should equal (DefaultEnv)
    r1.instances should equal (DefaultInstances)
    r1.cpus should equal (DefaultCpus)
    r1.mem should equal (DefaultMem)
    r1.disk should equal (DefaultDisk)
    r1.executor should equal (DefaultExecutor)
    r1.constraints should equal (DefaultConstraints)
    r1.uris should equal (DefaultUris)
    r1.storeUrls should equal (DefaultStoreUrls)
    r1.ports should equal (DefaultPorts)
    r1.requirePorts should equal (DefaultRequirePorts)
    r1.backoff should equal (DefaultBackoff)
    r1.backoffFactor should equal (DefaultBackoffFactor)
    r1.maxLaunchDelay should equal (DefaultMaxLaunchDelay)
    r1.container should equal (DefaultContainer)
    r1.healthChecks should equal (DefaultHealthChecks)
    r1.dependencies should equal (DefaultDependencies)
    r1.upgradeStrategy should equal (DefaultUpgradeStrategy)
    r1.acceptedResourceRoles should not be ('defined)
  }

  test("FromJSON should fail for empty id") {
    val json = Json.parse(""" { "id": "" }""")
    a[JsResultException] shouldBe thrownBy { json.as[V2AppDefinition] }
  }

  test("FromJSON should fail when using / as an id") {
    val json = Json.parse(""" { "id": "/" }""")
    a[JsResultException] shouldBe thrownBy { json.as[V2AppDefinition] }
  }

  test("FromJSON should not fail when 'cpus' is greater than 0") {
    val json = Json.parse(""" { "id": "test", "cpus": 0.0001 }""")
    noException should be thrownBy {
      json.as[V2AppDefinition]
    }
  }

  test("FromJSON should fail when 'cpus' is less than or equal to 0") {
    var json1 = Json.parse(""" { "id": "test", "cpus": 0.0 }""")
    a[JsResultException] shouldBe thrownBy { json1.as[V2AppDefinition] }

    val json2 = Json.parse(""" { "id": "test", "cpus": -1.0 }""")
    a[JsResultException] shouldBe thrownBy { json2.as[V2AppDefinition] }
  }

  test("""ToJSON should correctly handle missing acceptedResourceRoles""") {
    val appDefinition = V2AppDefinition(id = PathId("test"), acceptedResourceRoles = None)
    val json = Json.toJson(appDefinition)
    (json \ "acceptedResourceRoles").asOpt[Set[String]] should be(None)
  }

  test("""ToJSON should correctly handle acceptedResourceRoles""") {
    val appDefinition = V2AppDefinition(id = PathId("test"), acceptedResourceRoles = Some(Set("a")))
    val json = Json.toJson(appDefinition)
    (json \ "acceptedResourceRoles").asOpt[Set[String]] should be(Some(Set("a")))
  }

  test("""FromJSON should parse "acceptedResourceRoles": ["production", "*"] """) {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["production", "*"] }""")
    val appDef = json.as[V2AppDefinition]
    appDef.acceptedResourceRoles should equal(Some(Set("production", "*")))
  }

  test("""FromJSON should parse "acceptedResourceRoles": ["*"] """) {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["*"] }""")
    val appDef = json.as[V2AppDefinition]
    appDef.acceptedResourceRoles should equal(Some(Set("*")))
  }

  test("FromJSON should fail when 'acceptedResourceRoles' is defined but empty") {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": [] }""")
    a[JsResultException] shouldBe thrownBy { json.as[V2AppDefinition] }
  }
}

