package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.core.readiness.ReadinessCheckTestHelper
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.VersionInfo.OnlyVersion
import mesosphere.marathon.state._
import org.scalatest.Matchers
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class AppDefinitionFormatsTest extends UnitTest
    with AppAndGroupFormats
    with HealthCheckFormats
    with Matchers
    with FetchUriFormats
    with SecretFormats {

  import Formats.PathIdFormat

  object Fixture {
    val a1 = AppDefinition(
      id = "app1".toPath,
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

  "AppDefinitionFormats" should {
    "ToJson" in {
      import AppDefinition._
      import Fixture._

      val r1 = Json.toJson(a1)
      // check supplied values
      (r1 \ "id").get should equal (JsString("app1"))
      (r1 \ "cmd").get should equal (JsString("sleep 10"))
      (r1 \ "version").get should equal (JsString("1970-01-01T00:00:00.001Z"))
      (r1 \ "versionInfo").asOpt[JsObject] should equal(None)

      // check default values
      (r1 \ "args").asOpt[Seq[String]] should be (empty)
      (r1 \ "user").asOpt[String] should equal (None)
      (r1 \ "env").as[Map[String, String]] should equal (DefaultEnv)
      (r1 \ "instances").as[Long] should equal (DefaultInstances)
      (r1 \ "cpus").as[Double] should equal (DefaultCpus)
      (r1 \ "mem").as[Double] should equal (DefaultMem)
      (r1 \ "disk").as[Double] should equal (DefaultDisk)
      (r1 \ "gpus").as[Int] should equal (DefaultGpus)
      (r1 \ "executor").as[String] should equal (DefaultExecutor)
      (r1 \ "constraints").as[Set[Constraint]] should equal (DefaultConstraints)
      (r1 \ "uris").as[Seq[String]] should equal (DefaultUris)
      (r1 \ "fetch").as[Seq[FetchUri]] should equal (DefaultFetch)
      (r1 \ "storeUrls").as[Seq[String]] should equal (DefaultStoreUrls)
      (r1 \ "portDefinitions").as[Seq[PortDefinition]] should equal (DefaultPortDefinitions)
      (r1 \ "requirePorts").as[Boolean] should equal (DefaultRequirePorts)
      (r1 \ "backoffSeconds").as[Long] should equal (DefaultBackoff.toSeconds)
      (r1 \ "backoffFactor").as[Double] should equal (DefaultBackoffFactor)
      (r1 \ "maxLaunchDelaySeconds").as[Long] should equal (DefaultMaxLaunchDelay.toSeconds)
      (r1 \ "container").asOpt[String] should equal (None)
      (r1 \ "healthChecks").as[Set[HealthCheck]] should equal (DefaultHealthChecks)
      (r1 \ "dependencies").as[Set[PathId]] should equal (DefaultDependencies)
      (r1 \ "upgradeStrategy").as[UpgradeStrategy] should equal (DefaultUpgradeStrategy)
      (r1 \ "residency").asOpt[String] should equal (None)
      (r1 \ "secrets").as[Map[String, Secret]] should equal (DefaultSecrets)
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
      r1.resources.cpus should equal (DefaultCpus)
      r1.resources.mem should equal (DefaultMem)
      r1.resources.disk should equal (DefaultDisk)
      r1.resources.gpus should equal (DefaultGpus)
      r1.executor should equal (DefaultExecutor)
      r1.constraints should equal (DefaultConstraints)
      r1.fetch should equal (DefaultFetch)
      r1.storeUrls should equal (DefaultStoreUrls)
      r1.portDefinitions should equal (DefaultPortDefinitions)
      r1.requirePorts should equal (DefaultRequirePorts)
      r1.backoffStrategy.backoff should equal (DefaultBackoff)
      r1.backoffStrategy.factor should equal (DefaultBackoffFactor)
      r1.backoffStrategy.maxLaunchDelay should equal (DefaultMaxLaunchDelay)
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
        |  "version": "1970-01-01T00:00:00.002Z",
        |  "versionInfo": {
        |     "lastScalingAt": "1970-01-01T00:00:00.002Z",
        |     "lastConfigChangeAt": "1970-01-01T00:00:00.001Z"
        |  }
        |}""".stripMargin).as[AppDefinition]

      app.versionInfo shouldBe a[OnlyVersion]
    }

    "FromJSON should fail for empty id" in {
      val json = Json.parse(""" { "id": "" }""")
      a[JsResultException] shouldBe thrownBy { json.as[AppDefinition] }
    }

    "FromJSON should fail when using / as an id" in {
      val json = Json.parse(""" { "id": "/" }""")
      a[JsResultException] shouldBe thrownBy { json.as[AppDefinition] }
    }

    "FromJSON should not fail when 'cpus' is greater than 0" in {
      val json = Json.parse(""" { "id": "test", "cpus": 0.0001 }""")
      noException should be thrownBy {
        json.as[AppDefinition]
      }
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
      val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["production", "*"] }""")
      val appDef = json.as[AppDefinition]
      appDef.acceptedResourceRoles should equal(Set("production", ResourceRole.Unreserved))
    }

    """FromJSON should parse "acceptedResourceRoles": ["*"] """ in {
      val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": ["*"] }""")
      val appDef = json.as[AppDefinition]
      appDef.acceptedResourceRoles should equal(Set(ResourceRole.Unreserved))
    }

    "FromJSON should fail when 'acceptedResourceRoles' is defined but empty" in {
      val json = Json.parse(""" { "id": "test", "acceptedResourceRoles": [] }""")
      a[JsResultException] shouldBe thrownBy { json.as[AppDefinition] }
    }

    "FromJSON should read the default upgrade strategy" in {
      val json = Json.parse(""" { "id": "test" }""")
      val appDef = json.as[AppDefinition]
      appDef.upgradeStrategy should be(UpgradeStrategy.empty)
    }

    "FromJSON should read the residency upgrade strategy" in {
      val json = Json.parse(""" { "id": "test", "residency": {}}""")
      val appDef = json.as[AppDefinition]
      appDef.upgradeStrategy should be(UpgradeStrategy.forResidentTasks)
    }

    "FromJSON should read the default residency automatically residency " in {
      val json = Json.parse(
        """
        |{
        |  "id": "resident",
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
      val appDef = json.as[AppDefinition]
      appDef.residency should be(Some(Residency.defaultResidency))
    }

    """FromJSON should parse "residency" """ in {
      val appDef = Json.parse(
        """{
        |  "id": "test",
        |  "residency": {
        |     "relaunchEscalationTimeoutSeconds": 300,
        |     "taskLostBehavior": "RELAUNCH_AFTER_TIMEOUT"
        |  }
        |}""".stripMargin).as[AppDefinition]

      appDef.residency should equal(Some(Residency(300, Protos.ResidencyDefinition.TaskLostBehavior.RELAUNCH_AFTER_TIMEOUT)))
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
      ))
      val appJson = Json.toJson(app)
      val rereadApp = appJson.as[AppDefinition]
      rereadApp.readinessChecks should have size 1
      rereadApp should equal(app)
    }

    "FromJSON should parse ipAddress.networkName" in {
      val appDef = Json.parse(
        """{
        |  "id": "test",
        |  "ipAddress": {
        |    "networkName": "foo"
        |  }
        |}""".stripMargin).as[AppDefinition]

      appDef.ipAddress.isDefined && appDef.ipAddress.get.networkName.isDefined should equal(true)
      appDef.ipAddress.get.networkName should equal(Some("foo"))
    }

    "FromJSON should parse ipAddress.networkName with MESOS container" in {
      val appDef = Json.parse(
        """{
        |  "id": "test",
        |  "ipAddress": {
        |    "networkName": "foo"
        |  },
        |  "container": {
        |    "type": "MESOS"
        |  }
        |}""".stripMargin).as[AppDefinition]

      appDef.ipAddress.isDefined && appDef.ipAddress.get.networkName.isDefined should equal(true)
      appDef.ipAddress.get.networkName should equal(Some("foo"))
      appDef.container should be(defined)
      appDef.container.value shouldBe a[Container.Mesos]
    }

    "FromJSON should parse ipAddress.networkName with DOCKER container w/o port mappings" in {
      val appDef = Json.parse(
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
        |}""".stripMargin).as[AppDefinition]

      appDef.ipAddress.isDefined && appDef.ipAddress.get.networkName.isDefined should equal(true)
      appDef.ipAddress.get.networkName should equal(Some("foo"))
      appDef.container should be(defined)
      appDef.container.get shouldBe a[Container.Docker]
      appDef.container.flatMap(_.docker.flatMap(_.network.map(_.toString))) should equal (Some("USER"))
    }

    "FromJSON should parse ipAddress.networkName with DOCKER container w/ port mappings" in {
      val appDef = Json.parse(
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
        |}""".stripMargin).as[AppDefinition]

      appDef.ipAddress.isDefined && appDef.ipAddress.get.networkName.isDefined should equal(true)
      appDef.ipAddress.get.networkName should equal(Some("foo"))
      appDef.container should be(defined)
      appDef.container.get shouldBe a[Container.Docker]
      appDef.container.flatMap(_.docker.flatMap(_.network.map(_.toString))) should equal (Some("USER"))
      appDef.container.map(_.portMappings) should equal (Some(Seq(
        Container.PortMapping(containerPort = 123, servicePort = 80, name = Some("foobar"))
      )))
    }

    "FromJSON should throw a validation exception while parsing a Docker container with unsupported parameter" in {
      // credential is currently not supported
      intercept[JsResultException] {
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
          |}""".stripMargin).as[AppDefinition]
      } should have message ("JsResultException(errors:List((/container/credential,List(ValidationError(List(Docker Containerizer does not support credential),WrappedArray())))))")
    }

    "FromJSON should throw a validation exception while parsing a Mesos Docker container with unsupported parameter" in {
      // port mappings is currently not supported
      intercept[JsResultException] {
        Json.parse(
          """{
          |  "id": "test",
          |  "container": {
          |    "type": "MESOS",
          |    "docker": {
          |      "image": "busybox",
          |      "portMappings": [{"containerPort": 123, "servicePort": 80, "name": "foobar"}]
          |    }
          |  }
          |}""".stripMargin).as[AppDefinition]
      } should have message ("JsResultException(errors:List((/container/portMappings,List(ValidationError(List(Mesos Containerizer does not support portMappings),WrappedArray())))))")
      // network is currently not supported
      intercept[JsResultException] {
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
          |}""".stripMargin).as[AppDefinition]
      } should have message ("JsResultException(errors:List((/container/network,List(ValidationError(List(Mesos Containerizer does not support network),WrappedArray())))))")
      // parameters are currently not supported
      intercept[JsResultException] {
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
        |}""".stripMargin).as[AppDefinition]
      } should have message ("JsResultException(errors:List((/container/parameters,List(ValidationError(List(Mesos Containerizer does not support parameters),WrappedArray())))))")
    }

    "FromJSON should parse Mesos AppC container" in {
      val appDef = Json.parse(
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
        |}""".stripMargin).as[AppDefinition]

      appDef.ipAddress.isDefined && appDef.ipAddress.get.networkName.isDefined should equal(true)
      appDef.ipAddress.get.networkName should equal(Some("foo"))
      appDef.container should be(defined)
      appDef.container.get shouldBe a[Container.MesosAppC]
      appDef.container.get match {
        case ma: Container.MesosAppC =>
          ma.image should equal("busybox")
          ma.id should equal(Some("sha512-aHashValue"))
          ma.labels.keys.size should equal(3)
          ma.labels("version") should equal("1.2.0")
          ma.labels("arch") should equal("amd64")
          ma.labels("os") should equal("linux")
        case _ =>
      }
    }

    "FromJSON should parse ipAddress without networkName" in {
      val appDef = Json.parse(
        """{
        |  "id": "test",
        |  "ipAddress": { }
        |}""".stripMargin).as[AppDefinition]

      appDef.ipAddress.isDefined && appDef.ipAddress.get.networkName.isEmpty should equal(true)
    }

    "FromJSON should parse secrets" in {
      val appDef = Json.parse(
        """{
        |  "id": "test",
        |  "secrets": {
        |     "secret1": { "source": "/foo" },
        |     "secret2": { "source": "/foo" },
        |     "secret3": { "source": "/foo2" }
        |  }
        |}""".stripMargin).as[AppDefinition]

      appDef.secrets.keys.size should equal(3)
      appDef.secrets("secret1").source should equal("/foo")
      appDef.secrets("secret2").source should equal("/foo")
      appDef.secrets("secret3").source should equal("/foo2")
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
      val appDef = Json.parse(
        """{
        |  "id": "test",
        |  "unreachableStrategy": "disabled"
        |}""".stripMargin).as[AppDefinition]

      appDef.unreachableStrategy should be(UnreachableDisabled)
    }

    "FromJSON should parse unreachable enabled instance strategy" in {
      val appDef = Json.parse(
        """{
        |  "id": "test",
        |  "unreachableStrategy": {
        |      "inactiveAfterSeconds": 600,
        |      "expungeAfterSeconds": 1200
        |  }
        |}""".stripMargin).as[AppDefinition]

      appDef.unreachableStrategy should be(UnreachableEnabled(inactiveAfter = 10.minutes, expungeAfter = 20.minutes))
    }

    "ToJSON should serialize unreachable instance strategy" in {
      val strategy = UnreachableEnabled(6.minutes, 12.minutes)
      val appDef = AppDefinition(id = PathId("test"), unreachableStrategy = strategy)

      val json = Json.toJson(appDef)

      (json \ "unreachableStrategy" \ "inactiveAfterSeconds").as[Long] should be(360)
      (json \ "unreachableStrategy" \ "expungeAfterSeconds").as[Long] should be(720)
    }

    "FromJSON should parse kill selection" in {
      val appDef = Json.parse(
        """{
        |  "id": "test",
        |  "killSelection": "YOUNGEST_FIRST"
        |}""".stripMargin).as[AppDefinition]

      appDef.killSelection should be(KillSelection.YoungestFirst)
    }

    "FromJSON should fail for invalid kill selection" in {
      val json = Json.parse(
        """{
        |  "id": "test",
        |  "killSelection": "unknown"
        |}""".stripMargin)
      the[JsResultException] thrownBy {
        json.as[AppDefinition]
      } should have message ("JsResultException(errors:List((/killSelection,List(ValidationError(List(error.expected.jsstring),WrappedArray(KillSelection (OLDEST_FIRST, YOUNGEST_FIRST)))))))")
    }

    "ToJSON should serialize kill selection" in {
      val appDef = AppDefinition(id = PathId("test"), killSelection = KillSelection.OldestFirst)

      val json = Json.toJson(appDef)

      (json \ "killSelection").as[String] should be("OLDEST_FIRST")
    }

    "FromJSON should fail for empty container (#4978)" in {
      val json = Json.parse(
        """{
          |  "id": "docker-compose-demo",
          |  "cmd": "echo hello world",
          |  "container": {}
          |}""".stripMargin)
      the[JsResultException] thrownBy {
        json.as[AppDefinition]
      } should have message ("JsResultException(errors:List((/container/docker,List(ValidationError(List(error.path.missing),WrappedArray())))))")
    }
  }
}
