package mesosphere.marathon.api.v2.json

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.AppDefinition.VersionInfo.{ NoVersion, OnlyVersion, FullVersionInfo }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.scalatest.Matchers
import play.api.libs.json._

class AppDefinitionFormatsTest
    extends MarathonSpec
    with V2Formats
    with HealthCheckFormats
    with Matchers {

  import Formats.PathIdFormat

  object Fixture {
    val a1 = AppDefinition(
      id = "app1".toPath,
      cmd = Some("sleep 10"),
      versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(1))
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
    import AppDefinition._
    import Fixture._

    val r1 = Json.toJson(a1)
    // check supplied values
    (r1 \ "id").get should equal (JsString("app1"))
    (r1 \ "cmd").get should equal (JsString("sleep 10"))
    (r1 \ "version").get should equal (JsString("1970-01-01T00:00:00.001Z"))
    (r1 \ "versionInfo").asOpt[JsObject] should equal(None)

    // check default values
    (r1 \ "args").asOpt[Seq[String]] should equal (None)
    (r1 \ "user").asOpt[String] should equal (None)
    (r1 \ "env").as[Map[String, String]] should equal (DefaultEnv)
    (r1 \ "instances").as[Long] should equal (DefaultInstances)
    (r1 \ "cpus").as[Double] should equal (DefaultCpus)
    (r1 \ "mem").as[Double] should equal (DefaultMem)
    (r1 \ "disk").as[Double] should equal (DefaultDisk)
    (r1 \ "executor").as[String] should equal (DefaultExecutor)
    (r1 \ "constraints").as[Set[Constraint]] should equal (DefaultConstraints)
    (r1 \ "uris").as[Seq[String]] should equal (DefaultUris)
    (r1 \ "storeUrls").as[Seq[String]] should equal (DefaultStoreUrls)
    (r1 \ "ports").as[Seq[Long]] should equal (DefaultPorts.map(_.toInt))
    (r1 \ "requirePorts").as[Boolean] should equal (DefaultRequirePorts)
    (r1 \ "backoffSeconds").as[Long] should equal (DefaultBackoff.toSeconds)
    (r1 \ "backoffFactor").as[Double] should equal (DefaultBackoffFactor)
    (r1 \ "maxLaunchDelaySeconds").as[Long] should equal (DefaultMaxLaunchDelay.toSeconds)
    (r1 \ "container").asOpt[String] should equal (None)
    (r1 \ "healthChecks").as[Set[HealthCheck]] should equal (DefaultHealthChecks)
    (r1 \ "dependencies").as[Set[PathId]] should equal (DefaultDependencies)
    (r1 \ "upgradeStrategy").as[UpgradeStrategy] should equal (DefaultUpgradeStrategy)
  }

  test("ToJson should serialize full version info") {
    import Fixture._

    val r1 = Json.toJson(a1.copy(versionInfo = AppDefinition.VersionInfo.FullVersionInfo(
      version = Timestamp(3),
      lastScalingAt = Timestamp(2),
      lastConfigChangeAt = Timestamp(1)
    )))
    (r1 \ "version").as[String] should equal("1970-01-01T00:00:00.003Z")
    (r1 \ "versionInfo" \ "lastScalingAt").as[String] should equal("1970-01-01T00:00:00.002Z")
    (r1 \ "versionInfo" \ "lastConfigChangeAt").as[String] should equal("1970-01-01T00:00:00.001Z")
  }

  test("FromJson") {
    import AppDefinition._
    import Fixture._

    val r1 = j1.as[AppDefinition]
    // check supplied values
    r1.id should equal (a1.id)
    r1.cmd should equal (a1.cmd)
    r1.version should equal (Timestamp(1))
    r1.versionInfo shouldBe a[VersionInfo.OnlyVersion]
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

  test("FromJSON should ignore VersionInfo") {
    val app = Json.parse(
      """{
        |  "id": "test",
        |  "version": "1970-01-01T00:00:00.002Z",
        |  "versionInfo": {
        |     "lastScalingAt": "1970-01-01T00:00:00.002Z",
        |     "lastConfigChangeAt": "1970-01-01T00:00:00.001Z"
        |  }
        |}""".stripMargin).as[AppDefinition]

    app.versionInfo shouldBe a [OnlyVersion]
  }

  test("FromJSON should fail for empty id") {
    val json = Json.parse(""" { "id": "" }""")
    a[JsResultException] shouldBe thrownBy { json.as[AppDefinition] }
  }

  test("FromJSON should fail when using / as an id") {
    val json = Json.parse(""" { "id": "/" }""")
    a[JsResultException] shouldBe thrownBy { json.as[AppDefinition] }
  }

  test("FromJSON should not fail when 'cpus' is greater than 0") {
    val json = Json.parse(""" { "id": "test", "cpus": 0.0001 }""")
    noException should be thrownBy {
      json.as[AppDefinition]
    }
  }

  test("FromJSON should fail when 'cpus' is less than or equal to 0") {
    var json1 = Json.parse(""" { "id": "test", "cpus": 0.0 }""")
    a[JsResultException] shouldBe thrownBy { json1.as[AppDefinition] }

    val json2 = Json.parse(""" { "id": "test", "cpus": -1.0 }""")
    a[JsResultException] shouldBe thrownBy { json2.as[AppDefinition] }
  }

  test("""ToJSON should correctly handle missing acceptedResourceRoles""") {
    val appDefinition = AppDefinition(id = PathId("test"), acceptedResourceRoles = None)
    val json = Json.toJson(appDefinition)
    (json \ "acceptedResourceRoles").asOpt[Set[String]] should be(None)
  }

  test("""ToJSON should correctly handle acceptedResourceRoles""") {
    val appDefinition = AppDefinition(id = PathId("test"), acceptedResourceRoles = Some(Set("a")))
    val json = Json.toJson(appDefinition)
    (json \ "acceptedResourceRoles").asOpt[Set[String]] should be(Some(Set("a")))
  }

  test("""FromJSON should parse "acceptedResourceRoles": ["production", "*"] """) {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["production", "*"] }""")
    val appDef = json.as[AppDefinition]
    appDef.acceptedResourceRoles should equal(Some(Set("production", "*")))
  }

  test("""FromJSON should parse "acceptedResourceRoles": ["*"] """) {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["*"] }""")
    val appDef = json.as[AppDefinition]
    appDef.acceptedResourceRoles should equal(Some(Set("*")))
  }

  test("FromJSON should fail when 'acceptedResourceRoles' is defined but empty") {
    val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": [] }""")
    a[JsResultException] shouldBe thrownBy { json.as[AppDefinition] }
  }
}

