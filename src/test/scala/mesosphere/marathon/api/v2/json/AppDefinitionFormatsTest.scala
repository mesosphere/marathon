package mesosphere.marathon
package api.v2.json

import com.wix.accord.scalatest.ResultMatchers
import mesosphere.marathon.api.v2.{ AppNormalization, Validation }
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.core.pod.ContainerNetwork
import mesosphere.marathon.core.readiness.ReadinessCheckTestHelper
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.VersionInfo.OnlyVersion
import mesosphere.marathon.state._
import mesosphere.{ UnitTest, ValidationTestLike }
import org.scalatest.Matchers
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class AppDefinitionFormatsTest extends UnitTest
    with AppAndGroupFormats
    with HealthCheckFormats
    with Matchers
    with ResultMatchers
    with ValidationTestLike {

  import Formats.PathIdFormat

  object Fixture {
    val a1 = AppDefinition(
      id = "app1".toRootPath,
      cmd = Some("sleep 10"),
      versionInfo = VersionInfo.OnlyVersion(Timestamp(1))
    )

    val j1 = Json.parse("""
      {
        "id": "app1",
        "cmd": "sleep 10",
        "version": "1970-01-01T00:00:00.001Z"
      }
    """)
  }

  def normalizeAndConvert(app: raml.App): AppDefinition =
    Raml.fromRaml(
      // this is roughly the equivalent of how the original Formats behaved, which is notable because Formats
      // (like this code) reverses the order of validation and normalization
      Validation.validateOrThrow(
        AppNormalization.apply(AppNormalization.Configure(None))
          .normalized(Validation.validateOrThrow(
            AppNormalization.forDeprecated.normalized(app))(AppValidation.validateOldAppAPI)))(
          AppValidation.validateCanonicalAppAPI(Set.empty)
        )
    )

  "AppDefinitionFormats" should {
    "ToJson" in {
      import AppDefinition._
      import Fixture._
      import mesosphere.marathon.raml._

      val r1 = Json.toJson(a1)
      // check supplied values
      (r1 \ "id").get should equal (JsString("/app1"))
      (r1 \ "cmd").get should equal (JsString("sleep 10"))
      (r1 \ "version").get should equal (JsString("1970-01-01T00:00:00.001Z"))
      (r1 \ "versionInfo").asOpt[JsObject] should equal(None)

      // check default values
      (r1 \ "args").asOpt[Seq[String]] should be (empty)
      (r1 \ "user").asOpt[String] should equal (None)
      (r1 \ "env").asOpt[Map[String, String]] should be (empty)
      (r1 \ "instances").as[Long] should equal (App.DefaultInstances)
      (r1 \ "cpus").as[Double] should equal (App.DefaultCpus)
      (r1 \ "mem").as[Double] should equal (App.DefaultMem)
      (r1 \ "disk").as[Double] should equal (App.DefaultDisk)
      (r1 \ "gpus").as[Int] should equal (App.DefaultGpus)
      (r1 \ "executor").as[String] should equal (App.DefaultExecutor)
      (r1 \ "constraints").asOpt[Set[Seq[String]]] should be (empty)
      (r1 \ "fetch").asOpt[Seq[Artifact]] should be (empty)
      (r1 \ "storeUrls").asOpt[Seq[String]] should be (empty)
      (r1 \ "portDefinitions").asOpt[Seq[PortDefinition]] should equal (Option(Seq.empty))
      (r1 \ "requirePorts").as[Boolean] should equal (App.DefaultRequirePorts)
      (r1 \ "backoffSeconds").as[Long] should equal (App.DefaultBackoffSeconds)
      (r1 \ "backoffFactor").as[Double] should equal (App.DefaultBackoffFactor)
      (r1 \ "maxLaunchDelaySeconds").as[Long] should equal (App.DefaultMaxLaunchDelaySeconds)
      (r1 \ "container").asOpt[String] should equal (None)
      (r1 \ "healthChecks").asOpt[Seq[AppHealthCheck]] should be (empty)
      (r1 \ "dependencies").asOpt[Set[PathId]] should be (empty)
      (r1 \ "upgradeStrategy").as[UpgradeStrategy] should equal (DefaultUpgradeStrategy.toRaml)
      (r1 \ "residency").asOpt[String] should equal (None)
      (r1 \ "secrets").asOpt[Map[String, SecretDef]] should be (empty)
      (r1 \ "taskKillGracePeriodSeconds").asOpt[Long] should equal (DefaultTaskKillGracePeriod)
    }

    "ToJson should serialize full version info" in {
      import Fixture._

      val r1 = Json.toJson(a1.copy(versionInfo = VersionInfo.FullVersionInfo(
        version = Timestamp(3),
        lastScalingAt = Timestamp(2),
        lastConfigChangeAt = Timestamp(1)
      )))
      (r1 \ "version").as[String] should equal("1970-01-01T00:00:00.003Z")
      (r1 \ "versionInfo" \ "lastScalingAt").as[String] should equal("1970-01-01T00:00:00.002Z")
      (r1 \ "versionInfo" \ "lastConfigChangeAt").as[String] should equal("1970-01-01T00:00:00.001Z")
    }

    "FromJson" in {
      import AppDefinition._
      import Fixture._
      import raml.App

      val raw = j1.as[App]
      val r1 = normalizeAndConvert(raw)

      // check supplied values
      r1.id should equal (a1.id)
      r1.cmd should equal (a1.cmd)
      r1.version should equal (Timestamp(1))
      r1.versionInfo shouldBe a[VersionInfo.OnlyVersion]
      // check default values
      r1.args should equal (App.DefaultArgs)
      r1.user should equal (App.DefaultUser)
      r1.env should equal (DefaultEnv)
      r1.instances should equal (App.DefaultInstances)
      r1.resources.cpus should equal (App.DefaultCpus)
      r1.resources.mem should equal (App.DefaultMem)
      r1.resources.disk should equal (App.DefaultDisk)
      r1.resources.gpus should equal (App.DefaultGpus)
      r1.executor should equal (App.DefaultExecutor)
      r1.constraints should equal (DefaultConstraints)
      r1.fetch should equal (DefaultFetch)
      r1.storeUrls should equal (DefaultStoreUrls)
      r1.portDefinitions should equal (Seq(PortDefinition(port = 0, name = Some("default"))))
      r1.requirePorts should equal (App.DefaultRequirePorts)
      r1.backoffStrategy.backoff should equal (App.DefaultBackoffSeconds.seconds)
      r1.backoffStrategy.factor should equal (App.DefaultBackoffFactor)
      r1.backoffStrategy.maxLaunchDelay should equal (App.DefaultMaxLaunchDelaySeconds.seconds)
      r1.container should equal (DefaultContainer)
      r1.healthChecks should equal (DefaultHealthChecks)
      r1.dependencies should equal (DefaultDependencies)
      r1.upgradeStrategy should equal (DefaultUpgradeStrategy)
      r1.acceptedResourceRoles should be ('empty)
      r1.secrets should equal (DefaultSecrets)
      r1.taskKillGracePeriod should equal (DefaultTaskKillGracePeriod)
      r1.unreachableStrategy should equal (DefaultUnreachableStrategy)
    }

    "FromJSON should ignore VersionInfo" in {
      val app = Json.parse(
        """{
        |  "id": "test",
        |  "cmd": "foo",
        |  "version": "1970-01-01T00:00:00.002Z",
        |  "versionInfo": {
        |     "lastScalingAt": "1970-01-01T00:00:00.002Z",
        |     "lastConfigChangeAt": "1970-01-01T00:00:00.001Z"
        |  }
        |}""".stripMargin).as[raml.App]

      withValidationClue {
        val appDef = normalizeAndConvert(app)
        appDef.versionInfo shouldBe a[OnlyVersion]
      }
    }

    "FromJSON should fail for empty id" in {
      val json = Json.parse(""" { "id": "" }""")
      a[JsResultException] shouldBe thrownBy { normalizeAndConvert(json.as[raml.App]) }
    }

    "FromJSON should fail when using / as an id" in {
      val json = Json.parse(""" { "id": "/" }""")
      a[ValidationFailedException] shouldBe thrownBy { normalizeAndConvert(json.as[raml.App]) }
    }

    "FromJSON should not fail when 'cpus' is greater than 0" in {
      val json = Json.parse(""" { "id": "test", "cmd": "foo", "cpus": 0.0001 }""")
      noException should be thrownBy {
        normalizeAndConvert(json.as[raml.App])
      }
    }

    "FromJSON should fail when 'env' contains invalid keys" in {
      val json = Json.parse(""" { "id": "test", "cmd": "foo", "env": { "": "foo" } }""")
      a[ValidationFailedException] shouldBe thrownBy { normalizeAndConvert(json.as[raml.App]) }
    }

    """ToJSON should correctly handle missing acceptedResourceRoles""" in {
      val appDefinition = AppDefinition(id = PathId("test"), acceptedResourceRoles = Set.empty)
      val json = Json.toJson(appDefinition)
      (json \ "acceptedResourceRoles").asOpt[Set[String]] should be(None)
    }

    """ToJSON should correctly handle acceptedResourceRoles""" in {
      val appDefinition = AppDefinition(id = PathId("test"), acceptedResourceRoles = Set("a"))
      val json = Json.toJson(appDefinition)
      (json \ "acceptedResourceRoles").as[Set[String]] should be(Set("a"))
    }

    """FromJSON should parse "acceptedResourceRoles": ["production", "*"] """ in {
      val json = Json.parse(""" { "id": "test", "cmd": "foo", "acceptedResourceRoles": ["production", "*"] }""")
      val appDef = normalizeAndConvert(json.as[raml.App])
      appDef.acceptedResourceRoles should equal(Set("production", ResourceRole.Unreserved))
    }

    """FromJSON should parse "acceptedResourceRoles": ["*"] """ in {
      val json = Json.parse(""" { "id": "test", "cmd": "foo", "acceptedResourceRoles": ["*"] }""")
      val appDef = normalizeAndConvert(json.as[raml.App])
      appDef.acceptedResourceRoles should equal(Set(ResourceRole.Unreserved))
    }

    "FromJSON should fail when 'acceptedResourceRoles' is defined but empty" in {
      val json = Json.parse(""" { "id": "test", "cmd": "foo", "acceptedResourceRoles": [] }""")
      a[ValidationFailedException] shouldBe thrownBy { normalizeAndConvert(json.as[raml.App]) }
    }

    "FromJSON should read the default upgrade strategy" in {
      withValidationClue {
        val json = Json.parse(""" { "id": "test", "cmd": "foo" }""")
        val appDef = normalizeAndConvert(json.as[raml.App])
        appDef.upgradeStrategy should be(UpgradeStrategy.empty)
      }
    }

    "FromJSON should have a default residency upgrade strategy" in {
      withValidationClue {
        val json = Json.parse("""
          |{
          |  "id": "test",
          |  "container": {
          |    "type": "DOCKER",
          |    "docker": { "image": "busybox" },
          |    "volumes": [
          |      { "containerPath": "b", "persistent": { "size": 1 }, "mode": "RW" }
          |    ]
          |  },
          |  "residency": {}
          |}""".stripMargin)
        val appDef = normalizeAndConvert(json.as[raml.App])
        appDef.upgradeStrategy should be(UpgradeStrategy.forResidentTasks)
      }
    }

    "FromJSON should read the default residency automatically residency " in {
      withValidationClue {
        val json = Json.parse(
          """
        |{
        |  "id": "resident",
        |  "cmd": "foo",
        |  "container": {
        |    "type": "MESOS",
        |    "volumes": [{
        |      "containerPath": "var",
        |      "persistent": { "size": 10 },
        |      "mode": "RW"
        |    }]
        |  }
        |}
      """.stripMargin)
        val appDef = normalizeAndConvert(json.as[raml.App])
        appDef.residency should be(Some(Residency.default))
      }
    }

    """FromJSON should parse "residency" """ in {
      withValidationClue {
        val appDef = normalizeAndConvert(Json.parse(
          """{
        |  "id": "test",
        |  "cmd": "foo",
          |  "container": {
          |    "type": "MESOS",
          |    "volumes": [{
          |      "containerPath": "var",
          |      "persistent": { "size": 10 },
          |      "mode": "RW"
          |    }]
          |  },
          |  "residency": {
        |     "relaunchEscalationTimeoutSeconds": 300,
        |     "taskLostBehavior": "RELAUNCH_AFTER_TIMEOUT"
        |  }
        |}""".stripMargin).as[raml.App])

        appDef.residency should equal(Some(Residency(300, Protos.ResidencyDefinition.TaskLostBehavior.RELAUNCH_AFTER_TIMEOUT)))
      }
    }

    "ToJson should serialize residency" in {
      import Fixture._

      val json = Json.toJson(a1.copy(residency = Some(Residency(7200, Protos.ResidencyDefinition.TaskLostBehavior.WAIT_FOREVER))))
      (json \ "residency" \ "relaunchEscalationTimeoutSeconds").as[Long] should equal(7200)
      (json \ "residency" \ "taskLostBehavior").as[String] should equal(Protos.ResidencyDefinition.TaskLostBehavior.WAIT_FOREVER.name())
    }

    "AppDefinition JSON includes readinessChecks" in {
      val app = AppDefinition(id = PathId("/test"), cmd = Some("sleep 123"), readinessChecks = Seq(
        ReadinessCheckTestHelper.alternativeHttps
      ),
        portDefinitions = Seq(
          state.PortDefinition(0, name = Some(ReadinessCheckTestHelper.alternativeHttps.portName))
        )
      )
      val appJson = Json.toJson(app)
      val rereadApp = appJson.as[raml.App]
      rereadApp.readinessChecks should have size 1
      withValidationClue {
        normalizeAndConvert(rereadApp) should equal(app)
      }
    }

    "FromJSON should parse ipAddress.networkName" in {
      val appDef = normalizeAndConvert(Json.parse(
        """{
        |  "id": "test",
        |  "cmd": "foo",
        |  "ipAddress": {
        |    "networkName": "foo"
        |  }
        |}""".stripMargin).as[raml.App])

      appDef.networks should be(Seq(ContainerNetwork(name = "foo")))
    }

    "FromJSON should parse ipAddress.networkName with MESOS container" in {
      val appDef = normalizeAndConvert(Json.parse(
        """{
        |  "id": "test",
        |  "cmd": "foo",
        |  "ipAddress": {
        |    "networkName": "foo"
        |  },
        |  "container": {
        |    "type": "MESOS"
        |  }
        |}""".stripMargin).as[raml.App])

      appDef.networks should be(Seq(ContainerNetwork(name = "foo")))
      appDef.container should be(Some(Container.Mesos(portMappings = Seq(Container.PortMapping.defaultInstance))))
    }

    "FromJSON should parse ipAddress.networkName with DOCKER container w/o port mappings" in {
      withValidationClue {
        val appDef = normalizeAndConvert(Json.parse(
          """{
        |  "id": "test",
        |  "ipAddress": {
        |    "networkName": "foo"
        |  },
        |  "container": {
        |    "type": "DOCKER",
        |    "docker": {
        |      "image": "busybox",
        |      "network": "USER"
        |    }
        |  }
        |}""".stripMargin).as[raml.App])

        appDef.networks should be(Seq(ContainerNetwork(name = "foo")))
        appDef.container should be(Some(Container.Docker(image = "busybox", portMappings = Seq(Container.PortMapping.defaultInstance))))
      }
    }

    "FromJSON should parse ipAddress.networkName with DOCKER container w/ port mappings" in {
      val appDef = normalizeAndConvert(Json.parse(
        """{
        |  "id": "test",
        |  "ipAddress": {
        |    "networkName": "foo"
        |  },
        |  "container": {
        |    "type": "DOCKER",
        |    "docker": {
        |      "image": "busybox",
        |      "network": "USER",
        |      "portMappings": [{
        |        "containerPort": 123, "servicePort": 80, "name": "foobar"
        |      }]
        |    }
        |  }
        |}""".stripMargin).as[raml.App])

      appDef.networks should be(Seq(ContainerNetwork(name = "foo")))
      appDef.container should be(Some(Container.Docker(
        image = "busybox",
        portMappings = Seq(
          Container.PortMapping(containerPort = 123, servicePort = 80, name = Some("foobar"))
        )
      )))
    }

    "FromJSON should throw a validation exception while parsing a Docker container with unsupported parameter" in {
      // credential is currently not supported
      AppValidation.validateOldAppAPI(
        Json.parse(
        """{
          |  "id": "test",
          |  "container": {
          |    "type": "DOCKER",
          |    "docker": {
          |      "image": "busybox",
          |      "credential": {
          |        "principal": "aPrincipal",
          |        "secret": "aSecret"
          |      }
          |    }
          |  }
          |}""".stripMargin).as[raml.App]
      ) should failWith(
        GroupViolationMatcher(
          description = "container",
          constraint = "is invalid",
          violations = Set(
            group("docker", "is invalid", "credential" -> "must be empty")
          )
        )
      )
    }

    "FromJSON should parse Mesos Docker container" in {
      val appDef = normalizeAndConvert(Json.parse(
        """{
        |  "id": "test",
        |  "ipAddress": {
        |    "networkName": "foo"
        |  },
        |  "container": {
        |    "type": "MESOS",
        |    "docker": {
        |      "image": "busybox"
        |    }
        |  }
        |}""".stripMargin).as[raml.App])

      appDef.networks should be(Seq(core.pod.ContainerNetwork("foo")))
      appDef.container should be(Some(state.Container.MesosDocker(
        image = "busybox",
        portMappings = Seq(state.Container.PortMapping.defaultInstance)
      )))
    }

    "FromJSON should throw a validation exception while parsing a Mesos Docker container with unsupported parameter" in {
      // port mappings is currently not supported
      AppValidation.validateOldAppAPI(
        Json.parse(
        """{
          |  "id": "/test",
          |  "container": {
          |    "type": "MESOS",
          |    "docker": {
          |      "image": "busybox",
          |      "portMappings": [{"containerPort": 123, "servicePort": 80, "name": "foobar"}]
          |    }
          |  }
          |}""".stripMargin).as[raml.App]
      ) should failWith(GroupViolationMatcher(
        description = "container",
        constraint = "is invalid",
        violations = Set(
          group("docker", "is invalid", "portMappings" -> "must be empty")
        )))
      // network is currently not supported
      AppValidation.validateOldAppAPI(
        Json.parse(
        """{
          |  "id": "test",
          |  "container": {
          |    "type": "MESOS",
          |    "docker": {
          |      "image": "busybox",
          |      "network": "USER"
          |    }
          |  }
          |}""".stripMargin).as[raml.App]
      ) should failWith(GroupViolationMatcher(
        description = "container",
        constraint = "is invalid",
        violations = Set(
          group("docker", "is invalid", "network" -> "must be empty")
        )))
      // parameters are currently not supported
      AppValidation.validateOldAppAPI(
        Json.parse(
        """{
        |  "id": "test",
        |  "container": {
        |    "type": "MESOS",
        |    "docker": {
        |      "image": "busybox",
        |      "parameters": [ { "key": "a", "value": "b"} ]
        |    }
        |  }
        |}""".stripMargin).as[raml.App]
      ) should failWith(GroupViolationMatcher(
        description = "container",
        constraint = "is invalid",
        violations = Set(
          group("docker", "is invalid", "parameters" -> "must be empty")
        )))
    }

    "FromJSON should parse Mesos AppC container" in {
      val appDef = normalizeAndConvert(Json.parse(
        """{
        |  "id": "test",
        |  "ipAddress": {
        |    "networkName": "foo"
        |  },
        |  "container": {
        |    "type": "MESOS",
        |    "appc": {
        |      "image": "busybox",
        |      "id": "sha512-aHashValue",
        |      "labels": {
        |        "version": "1.2.0",
        |        "arch": "amd64",
        |        "os": "linux"
        |      }
        |    }
        |  }
        |}""".stripMargin).as[raml.App])

      appDef.networks should be(Seq(ContainerNetwork(name = "foo")))
      appDef.container should be(Some(Container.MesosAppC(
        image = "busybox",
        id = Some("sha512-aHashValue"),
        labels = Map(
          "version" -> "1.2.0",
          "arch" -> "amd64",
          "os" -> "linux"
        ),
        portMappings = Seq(Container.PortMapping.defaultInstance))))
    }

    "FromJSON should parse ipAddress without networkName" in {
      val app = Json.parse(
        """{
        |  "id": "test",
        |  "ipAddress": { }
        |}""".stripMargin).as[raml.App]

      app.ipAddress.isDefined && app.ipAddress.get.networkName.isEmpty should equal(true)
    }

    "FromJSON should parse secrets" in {
      val app = Json.parse(
        """{
        |  "id": "test",
        |  "secrets": {
        |     "secret1": { "source": "/foo" },
        |     "secret2": { "source": "/foo" },
        |     "secret3": { "source": "/foo2" }
        |  }
        |}""".stripMargin).as[raml.App]

      app.secrets.keys.size should equal(3)
      app.secrets("secret1").source should equal("/foo")
      app.secrets("secret2").source should equal("/foo")
      app.secrets("secret3").source should equal("/foo2")
    }

    "ToJSON should serialize secrets" in {
      import Fixture._

      val json = Json.toJson(a1.copy(secrets = Map(
        "secret1" -> Secret("/foo"),
        "secret2" -> Secret("/foo"),
        "secret3" -> Secret("/foo2")
      )))
      (json \ "secrets" \ "secret1" \ "source").as[String] should equal("/foo")
      (json \ "secrets" \ "secret2" \ "source").as[String] should equal("/foo")
      (json \ "secrets" \ "secret3" \ "source").as[String] should equal("/foo2")
    }

    "FromJSON should parse unreachable disabled instance strategy" in {
      val appDef = normalizeAndConvert(Json.parse(
        """{
        |  "id": "test",
        |  "cmd": "foo",
        |  "unreachableStrategy": "disabled"
        |}""".stripMargin).as[raml.App])

      appDef.unreachableStrategy should be(UnreachableDisabled)
    }

    "FromJSON should parse unreachable enabled instance strategy" in {
      val appDef = normalizeAndConvert(Json.parse(
        """{
        |  "id": "test",
        |  "cmd": "foo",
        |  "unreachableStrategy": {
        |      "inactiveAfterSeconds": 600,
        |      "expungeAfterSeconds": 1200
        |  }
        |}""".stripMargin).as[raml.App])

      appDef.unreachableStrategy should be(UnreachableEnabled(inactiveAfter = 10.minutes, expungeAfter = 20.minutes))
    }

    "ToJSON should serialize unreachable instance strategy" in {
      val strategy = UnreachableEnabled(6.minutes, 12.minutes)
      val appDef = AppDefinition(id = PathId("test"), unreachableStrategy = strategy)

      val json = Json.toJson(Raml.toRaml(appDef))

      (json \ "unreachableStrategy" \ "inactiveAfterSeconds").as[Long] should be(360)
      (json \ "unreachableStrategy" \ "expungeAfterSeconds").as[Long] should be(720)
    }

    "FromJSON should parse kill selection" in {
      val appDef = normalizeAndConvert(Json.parse(
        """{
        |  "id": "test",
        |  "cmd": "foo",
        |  "killSelection": "YOUNGEST_FIRST"
        |}""".stripMargin).as[raml.App])

      appDef.killSelection should be(KillSelection.YoungestFirst)
    }

    "FromJSON should fail for invalid kill selection" in {
      val json = Json.parse(
        """{
        |  "id": "test",
        |  "cmd": "foo",
        |  "killSelection": "unknown"
        |}""".stripMargin)
      the[JsResultException] thrownBy {
        normalizeAndConvert(json.as[raml.App])
      } should have message "JsResultException(errors:List((/killSelection,List(ValidationError(List(error.expected.jsstring),WrappedArray(KillSelection (OLDEST_FIRST, YOUNGEST_FIRST)))))))"
    }

    "ToJSON should serialize kill selection" in {
      val appDef = AppDefinition(id = PathId("test"), killSelection = KillSelection.OldestFirst)

      val json = Json.toJson(Raml.toRaml(appDef))

      (json \ "killSelection").as[String] should be("OLDEST_FIRST")
    }

    "app with readinessCheck passes validation" in {
      val app = AppDefinition(
        id = "test".toRootPath,
        cmd = Some("sleep 1234"),
        readinessChecks = Seq(
          ReadinessCheckTestHelper.alternativeHttps
        ),
        portDefinitions = Seq(state.PortDefinition(0, name = Some(ReadinessCheckTestHelper.alternativeHttps.portName)))
      )

      val jsonApp = Json.toJson(Raml.toRaml(app))
      withValidationClue {
        val ramlApp = jsonApp.as[raml.App]
        normalizeAndConvert(ramlApp)
      }
    }

    "FromJSON should fail for empty container (#4978)" in {
      val json = Json.parse(
        """{
          |  "id": "/docker-compose-demo",
          |  "cmd": "echo hello world",
          |  "container": {}
          |}""".stripMargin)
      val ramlApp = json.as[raml.App]
      AppValidation.validateCanonicalAppAPI(Set.empty)(ramlApp) should failWith(
        group("container", "is invalid", "docker" -> "not defined")
      )
    }
  }
}
