package mesosphere.marathon
package api.v2.json

import com.wix.accord._
import mesosphere.UnitTest
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.api.v2.Validation.validateOrThrow
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.api.v2.{ AppNormalization, AppsResource, ValidationHelper }
import mesosphere.marathon.core.readiness.ReadinessCheckTestHelper
import mesosphere.marathon.raml.{ AppCContainer, AppUpdate, Artifact, Container, ContainerPortMapping, DockerContainer, EngineType, Environment, Network, NetworkMode, PortDefinition, PortDefinitions, Raml, SecretDef, UpgradeStrategy }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => Mesos }
import play.api.libs.json.{ JsError, Json }

import scala.collection.immutable.Seq

class AppUpdateTest extends UnitTest {

  implicit val appUpdateValidator: Validator[AppUpdate] = AppValidation.validateCanonicalAppUpdateAPI(Set("secrets"))

  val runSpecId = PathId("/test")

  /**
    * @return an [[AppUpdate]] that's been normalized to canonical form
    */
  private[this] def fromJsonString(json: String): AppUpdate = {
    val update: AppUpdate = Json.fromJson[AppUpdate](Json.parse(json)).get
    AppNormalization.forDeprecatedUpdates(AppNormalization.Configuration(None, "bridge-name"))
      .normalized(validateOrThrow(update)(AppValidation.validateOldAppUpdateAPI))
  }

  def shouldViolate(update: AppUpdate, path: String, template: String): Unit = {
    val violations = Json.fromJson[AppUpdate](Json.toJson(update)) match {
      case err: JsError => ValidationHelper.getAllRuleConstrains(err)
      case obj => ValidationHelper.getAllRuleConstrains(validate(obj.get))
    }
    assert(violations.nonEmpty)
    assert(violations.exists(v =>
      v.path.getOrElse(false) == path && v.message == template
    ), s"expected path $path and message $template, but instead found" + violations)
  }

  def shouldNotViolate(update: AppUpdate, path: String, template: String): Unit = {
    val violations = Json.fromJson[AppUpdate](Json.toJson(update)) match {
      case err: JsError => ValidationHelper.getAllRuleConstrains(err)
      case obj => ValidationHelper.getAllRuleConstrains(validate(obj.get))
    }
    assert(!violations.exists(v =>
      v.path.getOrElse(false) == path && v.message == template))
  }

  "AppUpdate" should {
    "Validation" in {
      val update = AppUpdate()

      shouldViolate(
        update.copy(portDefinitions = Some(PortDefinitions(9000, 8080, 9000))),
        "/portDefinitions",
        "Ports must be unique."
      )

      shouldViolate(
        update.copy(portDefinitions = Some(Seq(
          PortDefinition(9000, name = Some("foo")),
          PortDefinition(9001, name = Some("foo"))))
        ),
        "/portDefinitions",
        "Port names must be unique."
      )

      shouldNotViolate(
        update.copy(portDefinitions = Some(Seq(
          PortDefinition(9000, name = Some("foo")),
          PortDefinition(9001, name = Some("bar"))))
        ),
        "/portDefinitions",
        "Port names must be unique."
      )

      shouldViolate(update.copy(mem = Some(-3.0)), "/mem", "error.min")
      shouldViolate(update.copy(cpus = Some(-3.0)), "/cpus", "error.min")
      shouldViolate(update.copy(disk = Some(-3.0)), "/disk", "error.min")
      shouldViolate(update.copy(instances = Some(-3)), "/instances", "error.min")
    }

    "Validate secrets" in {
      val update = AppUpdate()

      shouldViolate(update.copy(secrets = Some(Map(
        "a" -> SecretDef("")
      ))), "/secrets/a/source", "error.minLength")

      shouldViolate(update.copy(secrets = Some(Map(
        "" -> SecretDef("a/b/c")
      ))), "/secrets()/", "must not be empty")
    }

    "SerializationRoundtrip for empty definition" in {
      val update0 = AppUpdate(container = Some(Container(EngineType.Mesos)))
      JsonTestHelper.assertSerializationRoundtripWorks(update0)
    }

    "SerializationRoundtrip for definition with simple AppC container" in {
      val update0 = AppUpdate(container = Some(Container(EngineType.Mesos, appc = Some(AppCContainer(
        image = "anImage",
        labels = Map("key" -> "foo", "value" -> "bar")
      )))))
      JsonTestHelper.assertSerializationRoundtripWorks(update0)
    }

    "SerializationRoundtrip for extended definition" in {
      val update1 = AppUpdate(
        cmd = Some("sleep 60"),
        args = None,
        user = Some("nobody"),
        env = Some(Environment("LANG" -> "en-US")),
        instances = Some(16),
        cpus = Some(2.0),
        mem = Some(256.0),
        disk = Some(1024.0),
        executor = Some("/opt/executors/bin/some.executor"),
        constraints = Some(Set.empty),
        fetch = Some(Seq(Artifact(uri = "http://dl.corp.org/prodX-1.2.3.tgz"))),
        backoffSeconds = Some(2),
        backoffFactor = Some(1.2),
        maxLaunchDelaySeconds = Some(60),
        container = Some(Container(
          EngineType.Docker,
          docker = Some(DockerContainer(
            image = "docker:///group/image"
          )),
          portMappings = Option(Seq(
            ContainerPortMapping(containerPort = 80, name = Some("http"))
          ))
        )), healthChecks = Some(Set.empty),
        taskKillGracePeriodSeconds = Some(2),
        dependencies = Some(Set.empty),
        upgradeStrategy = Some(UpgradeStrategy(1, 1)),
        labels = Some(
          Map(
            "one" -> "aaa",
            "two" -> "bbb",
            "three" -> "ccc"
          )
        ),
        networks = Some(Seq(Network(
          mode = NetworkMode.Container,
          labels = Map(
            "foo" -> "bar",
            "baz" -> "buzz"

          )))),
        unreachableStrategy = Some(raml.UnreachableEnabled(998, 999))
      )
      JsonTestHelper.assertSerializationRoundtripWorks(update1)
    }

    "Serialization result of empty container" in {
      val update2 = AppUpdate(container = None)
      val json2 =
        """
      {
        "cmd": null,
        "user": null,
        "env": null,
        "instances": null,
        "cpus": null,
        "mem": null,
        "disk": null,
        "executor": null,
        "constraints": null,
        "uris": null,
        "ports": null,
        "backoffSeconds": null,
        "backoffFactor": null,
        "container": null,
        "healthChecks": null,
        "dependencies": null,
        "version": null
      }
    """
      val readResult2 = fromJsonString(json2)
      assert(readResult2 == update2)
    }

    "Serialization result of empty ipAddress" in {
      val update2 = AppUpdate(ipAddress = None)
      val json2 =
        """
      {
        "cmd": null,
        "user": null,
        "env": null,
        "instances": null,
        "cpus": null,
        "mem": null,
        "disk": null,
        "executor": null,
        "constraints": null,
        "uris": null,
        "ports": null,
        "backoffSeconds": null,
        "backoffFactor": null,
        "container": null,
        "healthChecks": null,
        "dependencies": null,
        "ipAddress": null,
        "version": null
      }
      """
      val readResult2 = fromJsonString(json2)
      assert(readResult2 == update2)
    }

    "Empty json corresponds to default instance" in {
      val update3 = AppUpdate()
      val json3 = "{}"
      val readResult3 = fromJsonString(json3)
      assert(readResult3 == update3)
    }

    "Args are correctly read" in {
      val update4 = AppUpdate(args = Some(Seq("a", "b", "c")))
      val json4 = """{ "args": ["a", "b", "c"] }"""
      val readResult4 = fromJsonString(json4)
      assert(readResult4 == update4)
    }

    "acceptedResourceRoles of update is only applied when != None" in {
      val app = AppDefinition(id = PathId("withAcceptedRoles"), acceptedResourceRoles = Set("a"))

      val unchanged = Raml.fromRaml(Raml.fromRaml((AppUpdate(), app))).copy(versionInfo = app.versionInfo)
      assert(unchanged == app)

      val changed = Raml.fromRaml(Raml.fromRaml((AppUpdate(acceptedResourceRoles = Some(Set("b"))), app))).copy(versionInfo = app.versionInfo)
      assert(changed == app.copy(acceptedResourceRoles = Set("b")))
    }

    "AppUpdate with a version and other changes are not allowed" in {
      val vfe = intercept[ValidationFailedException](validateOrThrow(
        AppUpdate(id = Some("/test"), cmd = Some("sleep 2"), version = Some(Timestamp(2).toOffsetDateTime))))
      assert(vfe.failure.violations.toString.contains("The 'version' field may only be combined with the 'id' field."))
    }

    "update may not have both uris and fetch" in {
      val json =
        """
      {
        "id": "app-with-network-isolation",
        "uris": ["http://example.com/file1.tar.gz"],
        "fetch": [{"uri": "http://example.com/file1.tar.gz"}]
      }
      """

      val vfe = intercept[ValidationFailedException](validateOrThrow(fromJsonString(json)))
      assert(vfe.failure.violations.toString.contains("may not be set in conjunction with fetch"))
    }

    "update may not have both ports and portDefinitions" in {
      val json =
        """
      {
        "id": "app",
        "ports": [1],
        "portDefinitions": [{"port": 2}]
      }
      """

      val vfe = intercept[ValidationFailedException](validateOrThrow(fromJsonString(json)))
      assert(vfe.failure.violations.toString.contains("cannot specify both ports and port definitions"))
    }

    "update may not have duplicated ports" in {
      val json =
        """
      {
        "id": "app",
        "ports": [1, 1]
      }
      """

      val vfe = intercept[ValidationFailedException](validateOrThrow(fromJsonString(json)))
      assert(vfe.failure.violations.toString.contains("ports must be unique"))
    }

    "update JSON serialization preserves readiness checks" in {
      val update = AppUpdate(
        id = Some("/test"),
        readinessChecks = Some(Seq(ReadinessCheckTestHelper.alternativeHttpsRaml))
      )
      val json = Json.toJson(update)
      val reread = json.as[AppUpdate]
      assert(reread == update)
    }

    "update readiness checks are applied to app" in {
      val update = AppUpdate(
        id = Some("/test"),
        readinessChecks = Some(Seq(ReadinessCheckTestHelper.alternativeHttpsRaml))
      )
      val app = AppDefinition(id = PathId("/test"))
      val updated = Raml.fromRaml(Raml.fromRaml((update, app)))

      assert(update.readinessChecks.map(_.map(Raml.fromRaml(_))).contains(updated.readinessChecks))
    }

    "empty app updateStrategy on persistent volumes" in {
      val json =
        """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        },
        "residency": {
          "relaunchEscalationTimeoutSeconds": 10,
          "taskLostBehavior": "WAIT_FOREVER"
        }
      }
      """

      val update = fromJsonString(json)
      val strategy = AppsResource.withoutPriorAppDefinition(update, "foo".toPath).upgradeStrategy
      assert(strategy.contains(raml.UpgradeStrategy(
        minimumHealthCapacity = 0.5,
        maximumOverCapacity = 0
      )))
    }

    "empty app residency on persistent volumes" in {
      val json =
        """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        },
        "upgradeStrategy": {
          "minimumHealthCapacity": 0.2,
          "maximumOverCapacity": 0
        }
      }
      """

      val update = fromJsonString(json)
      val residency = Raml.fromRaml(AppsResource.withoutPriorAppDefinition(update, "foo".toPath)).residency
      assert(residency.contains(Residency.default))
    }

    "empty app updateStrategy" in {
      val json =
        """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        },
        "residency": {
          "relaunchEscalationTimeoutSeconds": 10,
          "taskLostBehavior": "WAIT_FOREVER"
        }
      }
      """

      val update = fromJsonString(json)
      val strategy = AppsResource.withoutPriorAppDefinition(update, "foo".toPath).upgradeStrategy
      assert(strategy.contains(raml.UpgradeStrategy(
        minimumHealthCapacity = 0.5,
        maximumOverCapacity = 0
      )))
    }

    "empty app persists container" in {
      val json =
        """
        {
          "id": "/payload-id",
          "args": [],
          "container": {
            "type": "DOCKER",
            "volumes": [
              {
                "containerPath": "data",
                "mode": "RW",
                "persistent": {
                  "size": 100
                }
              }
            ],
            "docker": {
              "image": "anImage"
            }
          },
          "residency": {
            "taskLostBehavior": "WAIT_FOREVER",
            "relaunchEscalationTimeoutSeconds": 3600
          }
        }
      """

      val update = fromJsonString(json)
      val createdViaUpdate = Raml.fromRaml(AppsResource.withoutPriorAppDefinition(update, "/put-path-id".toPath))
      assert(update.container.isDefined)
      assert(createdViaUpdate.container.contains(state.Container.Docker(
        volumes = Seq(PersistentVolume("data", PersistentVolumeInfo(size = 100), mode = Mesos.Volume.Mode.RW)),
        image = "anImage"
      )), createdViaUpdate.container)
    }

    "empty app persists existing residency" in {
      val json =
        """
        {
          "id": "/app",
          "args": [],
          "container": {
            "type": "DOCKER",
            "volumes": [
              {
                "containerPath": "data",
                "mode": "RW",
                "persistent": {
                  "size": 100
                }
              }
            ],
            "docker": {
              "image": "anImage"
            }
          },
          "residency": {
            "taskLostBehavior": "WAIT_FOREVER",
            "relaunchEscalationTimeoutSeconds": 1234
          }
        }
      """

      val update = fromJsonString(json)
      val create = Raml.fromRaml(AppsResource.withoutPriorAppDefinition(update, "/app".toPath))
      assert(update.residency.isDefined)
      assert(update.residency.map(Raml.fromRaml(_)) == create.residency)
    }

    "empty app persists existing upgradeStrategy" in {
      val json =
        """
        {
          "id": "/app",
          "args": [],
          "container": {
            "type": "DOCKER",
            "volumes": [
              {
                "containerPath": "data",
                "mode": "RW",
                "persistent": {
                  "size": 100
                }
              }
            ],
            "docker": {
              "image": "anImage"
            }
          },
          "residency": {
            "taskLostBehavior": "WAIT_FOREVER",
            "relaunchEscalationTimeoutSeconds": 1234
          },
          "upgradeStrategy": {
            "minimumHealthCapacity": 0.1,
            "maximumOverCapacity": 0.0
          }
        }
      """

      val update = fromJsonString(json)
      val create = Raml.fromRaml(AppsResource.withoutPriorAppDefinition(update, "/app".toPath))
      assert(update.upgradeStrategy.isDefined)
      assert(update.upgradeStrategy.map(Raml.fromRaml(_)).contains(create.upgradeStrategy))
    }

    "empty app residency" in {
      val json =
        """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "home",
              "mode": "RW",
              "persistent": {
                "size": 100
                }
              }]
        },
        "upgradeStrategy": {
          "minimumHealthCapacity": 0.2,
          "maximumOverCapacity": 0
        }
      }
      """

      val update = fromJsonString(json)
      val residency = Raml.fromRaml(AppsResource.withoutPriorAppDefinition(update, "foo".toPath)).residency
      assert(residency.contains(Residency.default))
    }

    "empty app update strategy on external volumes" in {
      val json =
        """
      {
        "cmd": "sleep 1000",
        "container": {
          "type": "MESOS",
          "volumes": [
            {
              "containerPath": "/docker_storage",
              "mode": "RW",
              "external": {
                "name": "my-external-volume",
                "provider": "dvdi",
                "size": 1234
                }
              }]
        }
      }
      """

      val update = fromJsonString(json)
      val strategy = Raml.fromRaml(AppsResource.withoutPriorAppDefinition(update, "foo".toPath)).upgradeStrategy
      assert(strategy == state.UpgradeStrategy.forResidentTasks)
    }

    "container change in AppUpdate should be stored" in {
      val appDef = AppDefinition(id = runSpecId, container = Some(state.Container.Docker(image = "something")))
      // add port mappings..
      val appUpdate = AppUpdate(container = Some(Container(
        EngineType.Docker,
        docker = Some(DockerContainer(image = "something")), portMappings = Option(Seq(
          ContainerPortMapping(containerPort = 4000)
        ))
      )))
      val roundTrip = Raml.fromRaml((appUpdate, appDef))
      roundTrip.container should be('nonEmpty)
      roundTrip.container.foreach { container =>
        container.portMappings should be('nonEmpty)
        container.portMappings.flatMap(_.headOption.map(_.containerPort)) should contain(4000)
      }
    }

    "app update changes kill selection" in {
      val appDef = AppDefinition(id = runSpecId, killSelection = KillSelection.YoungestFirst)
      val update = AppUpdate(killSelection = Some(raml.KillSelection.OldestFirst))
      val result = Raml.fromRaml(update -> appDef)
      result.killSelection should be(raml.KillSelection.OldestFirst)
    }
  }
}
