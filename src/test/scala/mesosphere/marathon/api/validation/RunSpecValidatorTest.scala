package mesosphere.marathon
package api.validation

import com.wix.accord.validate
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.AppNormalization
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, MesosCommandHealthCheck }
import mesosphere.marathon.core.plugin.{ PluginDefinitions, PluginManager }
import mesosphere.marathon.core.pod.{ HostNetwork, Network }
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.raml.{ App, Apps, Raml, Resources }
import mesosphere.marathon.state._
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.{ Protos => mesos }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.reflect.ClassTag

class RunSpecValidatorTest extends UnitTest {

  private implicit lazy val validApp = AppValidation.validateCanonicalAppAPI(Set())
  private implicit lazy val validAppDefinition = AppDefinition.validAppDefinition(Set())(PluginManager.None)
  private def validContainer(networks: Seq[Network] = Nil) = Container.validContainer(networks, Set())

  private[this] def testValidId(id: String): Unit = {
    val app = AppDefinition(
      id = PathId(id),
      cmd = Some("true"))

    validate(app)
    MarathonTestHelper.validateJsonSchema(app)
  }

  private[this] def testInvalid(id: String): Unit = {
    val app = AppDefinition(
      id = PathId(id),
      cmd = Some("true")
    )

    val result = validate(app)
    result.isFailure should be(true)

    MarathonTestHelper.validateJsonSchema(app, valid = false)
  }

  "RunSpecValidator" should {
    "only cmd" in {
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("true"))

      validate(app)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "id '/app' is valid" in {
      testValidId("/app")
    }

    "id '/hy-phenated' is valid" in {
      testValidId("/hy-phenated")
    }

    "id '/numbered9' is valid" in {
      testValidId("/numbered9")
    }

    "id '/9numbered' is valid" in {
      testValidId("/9numbered")
    }

    "id '/num8bered' is valid" in {
      testValidId("/num8bered")
    }

    "id '/dot.ted' is valid" in {
      testValidId("/dot.ted")
    }

    "id '/deep/ly/nes/ted' is valid" in {
      testValidId("/deep/ly/nes/ted")
    }

    "id '/all.to-9gether/now-huh12/nest/nest' is valid" in {
      testValidId("/all.to-9gether/now-huh12/nest/nest")
    }

    "id '/trailing/' is valid" in {
      // the trailing slash is apparently ignored by Marathon
      testValidId("/trailing/")
    }

    "single dots in id '/test/.' pass schema and validation" in {
      testInvalid("/test/.")
    }

    "single dots in id '/./not.point.less' pass schema and validation" in {
      testInvalid("/./not.point.less")
    }

    // non-absolute paths (could be allowed in some contexts)
    "relative id 'relative/asd' passes schema but not validation" in {
      val app = AppDefinition(
        id = PathId("relative/asd"),
        cmd = Some("true"))

      the[ValidationFailedException] thrownBy validateOrThrow(app) should have message "Validation failed: Failure(Set(RuleViolation(relative/asd,Path needs to be absolute,Some(id))))"

      MarathonTestHelper.validateJsonSchema(app)
    }

    // non-absolute paths (could be allowed in some contexts)
    "relative id '../relative' passes schema but not validation" in {
      val app = AppDefinition(
        id = PathId("../relative"),
        cmd = Some("true"))

      the[ValidationFailedException] thrownBy validateOrThrow(app) should have message ("Validation failed: Failure(Set(RuleViolation(../relative,Path needs to be absolute,Some(id))))")

      MarathonTestHelper.validateJsonSchema(app)
    }

    "id '/.../asd' is INVALID" in {
      testInvalid("/.../asd")
    }

    "id '/app!' is INVALID" in {
      testInvalid("/app!' i")
    }

    "id '/app[' is INVALID" in {
      testInvalid("/app[' i")
    }

    "id '/asd/sadf+' is INVALID" in {
      testInvalid("/asd/sadf+")
    }

    "id '/asd asd' is INVALID" in {
      testInvalid("/asd asd")
    }

    "id '/app-' is invalid because hyphens and dots are only allowed inside of path fragments" in {
      testInvalid("/app-")
    }

    "id '/nest./ted' is invalid because hyphens and dots are only allowed inside of path fragments" in {
      testInvalid("/nest./ted")
    }

    "id '/nest/-ted' is invalid because hyphens and dots are only allowed inside of path fragments" in {
      testInvalid("/nest/-ted")
    }

    "only cmd + command health check" in {
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("true"),
        healthChecks = Set(
          MesosCommandHealthCheck(
            command = Command("curl http://localhost:$PORT")
          )
        )
      )
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "only cmd + acceptedResourceRoles" in {
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("true"),
        acceptedResourceRoles = Set(ResourceRole.Unreserved))
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "only cmd + acceptedResourceRoles 2" in {
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("true"),
        acceptedResourceRoles = Set(ResourceRole.Unreserved, "production"))
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "only args" in {
      val app = AppDefinition(
        id = PathId("/test"),
        args = "test" :: Nil)
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "only container" in {
      val f = new Fixture
      val app = AppDefinition(
        id = PathId("/test"),
        container = Some(f.validDockerContainer))
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "empty container is invalid" in {
      val app = AppDefinition(
        id = PathId("/test"),
        container = Some(Container.Mesos()))
      assert(validate(app).isFailure)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "docker container and cmd" in {
      val f = new Fixture
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("true"),
        container = Some(f.validDockerContainer))
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "docker container and args" in {
      val f = new Fixture
      val app = AppDefinition(
        id = PathId("/test"),
        args = "test" :: Nil,
        container = Some(f.validDockerContainer))
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "mesos container only" in {
      val f = new Fixture
      val app = AppDefinition(
        id = PathId("/test"),
        container = Some(f.validMesosDockerContainer))
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "mesos container and cmd" in {
      val f = new Fixture
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("true"),
        container = Some(f.validMesosDockerContainer))
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "mesos container and args" in {
      val f = new Fixture
      val app = AppDefinition(
        id = PathId("/test"),
        args = "test" :: Nil,
        container = Some(f.validMesosDockerContainer))
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "container, cmd and args is not valid" in {
      val f = new Fixture
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("true"),
        args = "test" :: Nil,
        container = Some(f.validDockerContainer))
      assert(validate(app).isFailure)
      MarathonTestHelper.validateJsonSchema(app, valid = false)
    }

    "container with type MESOS and empty docker field is valid" in {
      val f = new Fixture
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("true"),
        container = Some(f.validMesosContainer))
      assert(validate(app).isSuccess)
      MarathonTestHelper.validateJsonSchema(app)
    }

    "valid docker volume" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume)
      )
      assert(validate(container)(validContainer()).isSuccess)
    }

    "docker volume with missing containerPath is invalid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validDockerVolume.copy(containerPath = ""))
      )
      assert(validate(container)(validContainer()).isFailure)
    }

    "docker volume with missing hostPath is invalid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validDockerVolume.copy(hostPath = ""))
      )
      assert(validate(container)(validContainer()).isFailure)
    }

    "persistent volume with missing containerPath is invalid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume.copy(containerPath = ""))
      )
      assert(validate(container)(validContainer()).isFailure)
    }

    "persistent volume with mode RO is invalid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume.copy(mode = mesos.Volume.Mode.RO))
      )
      assert(validate(container)(validContainer()).isFailure)
    }

    "persistent volume with size 0 is invalid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume.copy(persistent = PersistentVolumeInfo(0)))
      )
      assert(validate(container)(validContainer()).isFailure)
    }

    "persistent volume with size < 0 is invalid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume.copy(persistent = PersistentVolumeInfo(-1)))
      )
      assert(validate(container)(validContainer()).isFailure)
    }

    "persistent volume with container path '.' is invalid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume.copy(containerPath = "."))
      )
      assert(validate(container)(validContainer()).isFailure)
    }

    "persistent volume with container path '..' is invalid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume.copy(containerPath = ".."))
      )
      assert(validate(container)(validContainer()).isFailure)
    }

    "persistent volume with container path '.hidden' is valid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume.copy(containerPath = ".hidden"))
      )
      assert(validate(container)(validContainer()).isSuccess)
    }

    "persistent volume with container path with dots in the middle is valid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume.copy(containerPath = "foo..bar"))
      )
      assert(validate(container)(validContainer()).isSuccess)
    }

    "persistent volume with container path starting with a forward slash is invalid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume.copy(containerPath = "/path"))
      )
      assert(validate(container)(validContainer()).isFailure)
    }

    "persistent volume with container path containing forward slashes is invalid" in {
      val f = new Fixture
      val container = f.validDockerContainer.copy(
        volumes = Seq(f.validPersistentVolume.copy(containerPath = "foo/bar"))
      )
      assert(validate(container)(validContainer()).isFailure)
    }

    "Validation for update of resident apps" in {
      Given("A resident app definition")
      val f = new Fixture
      val from = f.validResident

      When("Check if update to itself is valid")
      val to = from
      Then("Should be valid")
      AppDefinition.residentUpdateIsValid(from)(to).isSuccess should be(true)

      When("Check if default upgrade strategy is valid")
      val to2 = from.copy(upgradeStrategy = AppDefinition.DefaultUpgradeStrategy)
      Then("Should be invalid")
      AppDefinition.residentUpdateIsValid(from)(to2).isSuccess should be(false)

      When("Check if removing a volume is valid")
      val to3 = f.residentApp(from.id.toString, Seq(f.vol1))
      Then("Should be invalid")
      AppDefinition.residentUpdateIsValid(from)(to3).isSuccess should be(false)

      When("Check if adding a volume is valid")
      val to4 = f.residentApp(from.id.toString, Seq(f.vol1, f.vol2, f.vol3))
      Then("Should be invalid")
      AppDefinition.residentUpdateIsValid(from)(to4).isSuccess should be(false)

      When("Check if changing a volume is valid")
      val to5 = f.residentApp(from.id.toString, Seq(f.vol1, f.vol3))
      Then("Should be invalid")
      AppDefinition.residentUpdateIsValid(from)(to5).isSuccess should be(false)

      When("Check if changing mem is valid")
      val to6 = from.copy(resources = Resources(mem = 123))
      Then("Should be invalid")
      AppDefinition.residentUpdateIsValid(from)(to6).isSuccess should be(false)

      When("Check if changing cpu is valid")
      val to7 = from.copy(resources = Resources(cpus = 123))
      Then("Should be invalid")
      AppDefinition.residentUpdateIsValid(from)(to7).isSuccess should be(false)

      When("Check if changing disk is valid")
      val to8 = from.copy(resources = Resources(disk = 123))
      Then("Should be invalid")
      AppDefinition.residentUpdateIsValid(from)(to8).isSuccess should be(false)

      When("Check if changing ports is valid")
      val to9 = from.copy(portDefinitions = Seq.empty)
      Then("Should be invalid")
      AppDefinition.residentUpdateIsValid(from)(to9).isSuccess should be(false)
    }

    "Validation for defining a resident app" in {
      Given("A resident app definition")
      val f = new Fixture
      val from = f.validResident

      When("Check if only defining residency without persistent volumes is valid")
      val to1 = from.copy(container = None)
      Then("Should be invalid")
      validAppDefinition(to1).isSuccess should be(false)

      When("Check if only defining local volumes without residency is valid")
      val to2 = from.copy(residency = None)
      Then("Should be invalid")
      validAppDefinition(to2).isSuccess should be(false)

      When("Check if defining local volumes and residency is valid")
      Then("Should be valid")
      validAppDefinition(from).isSuccess should be(true)

      When("Check if defining no local volumes and no residency is valid")
      val to3 = from.copy(residency = None, container = None)
      Then("Should be valid")
      validAppDefinition(to3).isSuccess should be(true)
    }

    "A application with label MARATHON_SINGLE_INSTANCE_APP may not have an instance count > 1" in {
      Given("an app with label MARATHON_SINGLE_INSTANCE_APP and an instance count of 0")
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("sleep 1000"),
        instances = 0,
        upgradeStrategy = UpgradeStrategy(0, 0),
        labels = Map[String, String](
          Apps.LabelSingleInstanceApp -> true.toString
        )
      )
      Then("the validation succeeds")
      validAppDefinition(app).isSuccess shouldBe true

      When("the instance count is set to 1")
      val appWith1Instance = app.copy(instances = 1)
      Then("the validation succeeds")
      validAppDefinition(appWith1Instance).isSuccess shouldBe true

      When("the instance count is set to 2")
      val appWith2Instances = app.copy(instances = 2)
      Then("the validation fails")
      validAppDefinition(appWith2Instances).isFailure shouldBe true
    }

    "For an application with label MARATHON_SINGLE_INSTANCE_APP UpgradeStrategy(1,0) is invalid" in {
      Given("an app with label MARATHON_SINGLE_INSTANCE_APP and an UpgradeStrategy(1,0)")
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("sleep 1000"),
        upgradeStrategy = UpgradeStrategy(1, 0),
        labels = Map[String, String](
          Apps.LabelSingleInstanceApp -> true.toString
        )
      )
      Then("the validation fails")
      validAppDefinition(app).isFailure shouldBe true
    }

    "For an application with label MARATHON_SINGLE_INSTANCE_APP UpgradeStrategy(1,1) is invalid" in {
      Given("an app with label MARATHON_SINGLE_INSTANCE_APP and an UpgradeStrategy(1,1)")
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("sleep 1000"),
        upgradeStrategy = UpgradeStrategy(1, 1),
        labels = Map[String, String](
          Apps.LabelSingleInstanceApp -> true.toString
        )
      )
      Then("the validation fails")
      validAppDefinition(app).isFailure shouldBe true
    }

    "For an application with label MARATHON_SINGLE_INSTANCE_APP UpgradeStrategy(0,1) is invalid" in {
      Given("an app with label MARATHON_SINGLE_INSTANCE_APP and an UpgradeStrategy(0,1)")
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("sleep 1000"),
        upgradeStrategy = UpgradeStrategy(0, 1),
        labels = Map[String, String](
          Apps.LabelSingleInstanceApp -> true.toString
        )
      )
      Then("the validation fails")
      validAppDefinition(app).isFailure shouldBe true
    }

    "For an application with label MARATHON_SINGLE_INSTANCE_APP UpgradeStrategy(0,0) is valid" in {
      Given("an app with label MARATHON_SINGLE_INSTANCE_APP and an UpgradeStrategy(0,0)")
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("sleep 1000"),
        upgradeStrategy = UpgradeStrategy(0, 0),
        labels = Map[String, String](
          Apps.LabelSingleInstanceApp -> true.toString
        )
      )
      Then("the validation fails")
      validAppDefinition(app).isSuccess shouldBe true
    }

    "readinessChecks are invalid for normal apps" in {
      Given("a normal app with a defined readinessCheck")
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("true"),
        readinessChecks = Seq(ReadinessCheck()))

      Then("validation fails")
      validAppDefinition(app).isFailure shouldBe true
    }

    "Resident app may only define unreserved acceptedResourceRoles or None" in {
      Given("A resident app definition")
      val f = new Fixture
      val from = f.validResident

      When("validating with role for static reservation")
      val to1 = from.copy(acceptedResourceRoles = Set("foo"))
      Then("Should be invalid")
      validAppDefinition(to1).isSuccess shouldBe false

      When("validating with only unreserved roles")
      val to2 = from.copy(acceptedResourceRoles = Set(ResourceRole.Unreserved))
      Then("Should be valid")
      validAppDefinition(to2).isSuccess shouldBe true

      When("validating without acceptedResourceRoles")
      val to3 = from.copy(acceptedResourceRoles = Set.empty)
      Then("Should be valid")
      validAppDefinition(to3).isSuccess shouldBe true
    }

    "health check validation should allow port specifications without port indices" in {
      Given("A docker app with no portDefinitions and HTTP health checks")

      val app1 = AppDefinition(
        id = PathId("/test"),
        networks = Seq(HostNetwork), container = Some(Container.Docker(
          image = "group/image"
        )),
        portDefinitions = List.empty,
        healthChecks = Set(
          MarathonHttpHealthCheck(
            path = Some("/"),
            protocol = Protos.HealthCheckDefinition.Protocol.HTTP,
            port = Some(8000),
            portIndex = None
          )
        )
      )
      Then("validation succeeds")
      validAppDefinition(app1).isSuccess shouldBe true
    }

    "cassandraWithoutResidency" in {

      val f = new Fixture
      val app = Json.parse(f.cassandraWithoutResidency).as[App]
      val config = AppNormalization.Configuration(None, "bridge-name")
      val result = validAppDefinition(Raml.fromRaml(
        AppNormalization(config).normalized(
          validateOrThrow(
            AppNormalization.forDeprecated(config).normalized(app)))))
      result.isSuccess shouldBe true
    }

    "cassandraWithoutResidencyWithUpgradeStrategy" in {

      val f = new Fixture
      val base = Json.parse(f.cassandraWithoutResidency).as[App]
      val app = base.copy(upgradeStrategy = Some(raml.UpgradeStrategy(0, 0)))
      val config = AppNormalization.Configuration(None, "bridge-name")
      val result = validAppDefinition(Raml.fromRaml(
        AppNormalization(config).normalized(
          validateOrThrow(
            AppNormalization.forDeprecated(config).normalized(app)))))
      withClue(result) {
        result.isSuccess shouldBe true
      }
    }

    "Validation plugins can invalidate apps" in {
      Given("An app with an invalid label")
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("sleep 1000"),
        upgradeStrategy = UpgradeStrategy(0, 0),
        env = Map[String, EnvVarValue]("SECURITY_USER" -> new EnvVarString("admin"))
      )
      Then("the validation fails")
      val pm = new PluginManager() {
        def plugins[T](implicit ct: ClassTag[T]): Seq[T] = {
          ct.toString() match {
            case "mesosphere.marathon.plugin.validation.RunSpecValidator" =>
              List(
                isTrue[mesosphere.marathon.plugin.ApplicationSpec]("SECURITY_* environment variables are not permitted") {
                _.env.keys.count(_.startsWith("SECURITY_")) == 0
              }.asInstanceOf[T]
              )
            case _ => List.empty
          }
        }
        def definitions: PluginDefinitions = PluginDefinitions.None
      }
      AppDefinition.validAppDefinition(Set())(pm)(app).isFailure shouldBe true

      Given("An app without an invalid label")
      val app2 = AppDefinition(
        id = PathId("/test"),
        cmd = Some("sleep 1000"),
        upgradeStrategy = UpgradeStrategy(0, 0),
        env = EnvVarValue(Map[String, String](
          "APP_USER" -> "admin"
        ))
      )
      Then("the validation succeeds")
      AppDefinition.validAppDefinition(Set())(pm)(app2).isSuccess shouldBe true
    }

    class Fixture {
      def validDockerContainer: Container.Docker = Container.Docker(
        volumes = Nil,
        image = "foo/bar:latest"
      )

      def validMesosContainer: Container.Mesos = Container.Mesos(
        volumes = Nil
      )

      def validMesosDockerContainer: Container.MesosDocker = Container.MesosDocker(
        volumes = Nil,
        image = "foo/bar:latest"
      )

      // scalastyle:off magic.number
      def validPersistentVolume: PersistentVolume = PersistentVolume(
        containerPath = "test",
        persistent = PersistentVolumeInfo(10),
        mode = mesos.Volume.Mode.RW)

      def validDockerVolume: DockerVolume = DockerVolume(
        containerPath = "/test",
        hostPath = "/etc/foo",
        mode = mesos.Volume.Mode.RW)

      def persistentVolume(path: String) = PersistentVolume(path, PersistentVolumeInfo(123), mesos.Volume.Mode.RW)
      val zero = UpgradeStrategy(0, 0)

      def residentApp(id: String, volumes: Seq[PersistentVolume]): AppDefinition = {
        AppDefinition(
          id = PathId(id),
          cmd = Some("test"),
          container = Some(Container.Mesos(volumes)),
          residency = Some(Residency(123, Protos.ResidencyDefinition.TaskLostBehavior.RELAUNCH_AFTER_TIMEOUT)),
          portDefinitions = Seq(PortDefinition(0)),
          unreachableStrategy = UnreachableStrategy.default(resident = true)
        )
      }
      val vol1 = persistentVolume("foo")
      val vol2 = persistentVolume("bla")
      val vol3 = persistentVolume("test")
      val validResident = residentApp("/app1", Seq(vol1, vol2)).copy(upgradeStrategy = zero)

      def cassandraWithoutResidency =
        """
        |{
        |  "id": "/cassandra",
        |  "cpus": 2,
        |  "mem": 2048,
        |  "instances": 1,
        |  "constraints": [
        |    [
        |      "hostname",
        |      "UNIQUE"
        |    ]
        |  ],
        |  "container": {
        |    "type": "DOCKER",
        |    "docker": {
        |      "image": "tobert/cassandra",
        |      "network": "BRIDGE",
        |      "forcePullImage": true,
        |      "portMappings": [
        |        {
        |          "containerPort": 7000,
        |          "hostPort": 7000,
        |          "protocol": "tcp"
        |        },
        |        {
        |          "containerPort": 7199,
        |          "hostPort": 7199,
        |          "protocol": "tcp"
        |        },
        |        {
        |          "containerPort": 9042,
        |          "hostPort": 9042,
        |          "protocol": "tcp"
        |        },
        |        {
        |          "containerPort": 9160,
        |          "hostPort": 9160,
        |          "protocol": "tcp"
        |        }
        |      ]
        |    },
        |    "volumes": [
        |      {
        |        "containerPath": "/data",
        |        "hostPath": "cassandradata",
        |        "mode": "RW"
        |      },
        |      {
        |        "containerPath": "cassandradata",
        |        "mode": "RW",
        |        "persistent": {
        |          "size": 1000
        |        }
        |      }
        |    ]
        |  },
        |  "healthChecks": [
        |    {
        |      "protocol": "TCP",
        |      "portIndex": 3,
        |      "gracePeriodSeconds": 5,
        |      "intervalSeconds": 20,
        |      "maxConsecutiveFailures": 3
        |    }
        |  ]
        |}
      """.stripMargin
    }
  }
}
