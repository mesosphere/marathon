package mesosphere.marathon.api.validation

import mesosphere.marathon.Protos.HealthCheckDefinition
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state._
import mesosphere.marathon._
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers

import scala.collection.immutable.Seq

class AppDefinitionValidatorTest extends MarathonSpec with Matchers {

  class Fixture {
    def validDockerContainer: Container = Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      volumes = Nil,
      docker = Some(Docker(image = "foo/bar:latest"))
    )

    def invalidDockerContainer: Container = Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      volumes = Nil,
      docker = None
    )

    def validMesosContainer: Container = Container(
      `type` = mesos.ContainerInfo.Type.MESOS,
      volumes = Nil,
      docker = None
    )

    def invalidMesosContainer: Container = Container(
      `type` = mesos.ContainerInfo.Type.MESOS,
      volumes = Nil,
      docker = Some(Docker(image = "foo/bar:latest"))
    )

    // scalastyle:off magic.number
    def validPersistentVolume: PersistentVolume = PersistentVolume(
      containerPath = "/test",
      persistent = PersistentVolumeInfo(10),
      mode = mesos.Volume.Mode.RW)

    def validDockerVolume: DockerVolume = DockerVolume(
      containerPath = "/test",
      hostPath = "/etc/foo",
      mode = mesos.Volume.Mode.RW)

  }

  test("only cmd") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"))
    validate(app)
    MarathonTestHelper.validateJsonSchema(app)
  }

  private[this] def testValidId(id: String): Unit = {
    val app = AppDefinition(
      id = PathId(id),
      cmd = Some("true"))

    validate(app)
    MarathonTestHelper.validateJsonSchema(app)
  }

  test("id '/app' is valid") {
    testValidId("/app")
  }

  test("id '/hy-phenated' is valid") {
    testValidId("/hy-phenated")
  }

  test("id '/numbered9' is valid") {
    testValidId("/numbered9")
  }

  test("id '/9numbered' is valid") {
    testValidId("/9numbered")
  }

  test("id '/num8bered' is valid") {
    testValidId("/num8bered")
  }

  test("id '/dot.ted' is valid") {
    testValidId("/dot.ted")
  }

  test("id '/deep/ly/nes/ted' is valid") {
    testValidId("/deep/ly/nes/ted")
  }

  test("id '/all.to-9gether/now-huh12/nest/nest' is valid") {
    testValidId("/all.to-9gether/now-huh12/nest/nest")
  }

  test("id '/trailing/' is valid") {
    // the trailing slash is apparently ignored by Marathon
    testValidId("/trailing/")
  }

  test("single dots in id '/test/.' pass schema and validation") {
    testInvalid("/test/.")
  }

  test("single dots in id '/./not.point.less' pass schema and validation") {
    testInvalid("/./not.point.less")
  }

  private[this] def testSchemaLessStrictForId(id: String): Unit = {
    val app = AppDefinition(
      id = PathId(id),
      cmd = Some("true"))

    an[ValidationFailedException] should be thrownBy validateOrThrow(app)

    MarathonTestHelper.validateJsonSchema(app)
  }

  // non-absolute paths (could be allowed in some contexts)
  test(s"relative id 'relative/asd' passes schema but not validation") {
    testSchemaLessStrictForId("relative/asd")
  }

  // non-absolute paths (could be allowed in some contexts)
  test(s"relative id '../relative' passes schema but not validation") {
    testSchemaLessStrictForId("../relative")
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

  test("id '/.../asd' is INVALID") {
    testInvalid("/.../asd")
  }

  test("id '/app!' is INVALID") {
    testInvalid("/app!' i")
  }

  test("id '/app[' is INVALID") {
    testInvalid("/app[' i")
  }

  test("id '/asd/sadf+' is INVALID") {
    testInvalid("/asd/sadf+")
  }

  test("id '/asd asd' is INVALID") {
    testInvalid("/asd asd")
  }

  test("id '/app-' is invalid because hyphens and dots are only allowed inside of path fragments") {
    testInvalid("/app-")
  }

  test("id '/nest./ted' is invalid because hyphens and dots are only allowed inside of path fragments") {
    testInvalid("/nest./ted")
  }

  test("id '/nest/-ted' is invalid because hyphens and dots are only allowed inside of path fragments") {
    testInvalid("/nest/-ted")
  }

  test("only cmd + command health check") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      healthChecks = Set(
        HealthCheck(
          protocol = HealthCheckDefinition.Protocol.COMMAND,
          command = Some(Command("curl http://localhost:$PORT"))
        )
      )
    )
    assert(validate(app).isSuccess)
    MarathonTestHelper.validateJsonSchema(app)
  }

  test("only cmd + acceptedResourceRoles") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      acceptedResourceRoles = Some(Set("*")))
    assert(validate(app).isSuccess)
    MarathonTestHelper.validateJsonSchema(app)
  }

  test("only cmd + acceptedResourceRoles 2") {
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      acceptedResourceRoles = Some(Set("*", "production")))
    assert(validate(app).isSuccess)
    MarathonTestHelper.validateJsonSchema(app)
  }

  test("only args") {
    val app = AppDefinition(
      id = PathId("/test"),
      args = Some("test" :: Nil))
    assert(validate(app).isSuccess)
    MarathonTestHelper.validateJsonSchema(app)
  }

  test("only container") {
    val f = new Fixture
    val app = AppDefinition(
      id = PathId("/test"),
      container = Some(f.validDockerContainer))
    assert(validate(app).isSuccess)
    MarathonTestHelper.validateJsonSchema(app)
  }

  test("empty container is invalid") {
    val app = AppDefinition(
      id = PathId("/test"),
      container = Some(Container()))
    assert(validate(app).isFailure)
    MarathonTestHelper.validateJsonSchema(app)
  }

  test("container with type DOCKER and empty docker field is invalid") {
    val f = new Fixture
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      container = Some(f.invalidDockerContainer))
    assert(validate(app).isFailure)
    MarathonTestHelper.validateJsonSchema(app, valid = true)
  }

  test("container and cmd") {
    val f = new Fixture
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      container = Some(f.validDockerContainer))
    assert(validate(app).isSuccess)
    MarathonTestHelper.validateJsonSchema(app)
  }

  test("container and args") {
    val f = new Fixture
    val app = AppDefinition(
      id = PathId("/test"),
      args = Some("test" :: Nil),
      container = Some(f.validDockerContainer))
    assert(validate(app).isSuccess)
    MarathonTestHelper.validateJsonSchema(app)
  }

  test("container, cmd and args is not valid") {
    val f = new Fixture
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      args = Some("test" :: Nil),
      container = Some(f.validDockerContainer))
    assert(validate(app).isFailure)
    MarathonTestHelper.validateJsonSchema(app, valid = false)
  }

  test("container with type MESOS and nonEmpty docker field is invalid") {
    val f = new Fixture
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      container = Some(f.invalidMesosContainer))
    assert(validate(app).isFailure)
    MarathonTestHelper.validateJsonSchema(app, valid = true)
  }

  test("container with type MESOS and empty docker field is valid") {
    val f = new Fixture
    val app = AppDefinition(
      id = PathId("/test"),
      cmd = Some("true"),
      container = Some(f.validMesosContainer))
    assert(validate(app).isSuccess)
    MarathonTestHelper.validateJsonSchema(app, valid = true)
  }

  test("valid docker volume") {
    AllConf.SuppliedOptionNames = Set("mesos_authentication_principal", "mesos_role", "mesos_authentication_secret_file")
    val f = new Fixture
    val container = f.validDockerContainer.copy(
      volumes = Seq(f.validPersistentVolume)
    )
    assert(validate(container).isSuccess)
  }

  test("valid docker volume, but cli parameter are not provided") {
    AllConf.SuppliedOptionNames = Set.empty
    val f = new Fixture
    val container = f.validDockerContainer.copy(
      volumes = Seq(f.validPersistentVolume)
    )
    assert(validate(container).isFailure)
  }

  test("docker volume with missing containerPath is invalid") {
    val f = new Fixture
    val container = f.validDockerContainer.copy(
      volumes = Seq(f.validDockerVolume.copy(containerPath = ""))
    )
    assert(validate(container).isFailure)
  }

  test("docker volume with missing hostPath is invalid") {
    val f = new Fixture
    val container = f.validDockerContainer.copy(
      volumes = Seq(f.validDockerVolume.copy(hostPath = ""))
    )
    assert(validate(container).isFailure)
  }

  test("persistent volume with missing containerPath is invalid") {
    val f = new Fixture
    val container = f.validDockerContainer.copy(
      volumes = Seq(f.validPersistentVolume.copy(containerPath = ""))
    )
    assert(validate(container).isFailure)
  }

  test("persistent volume with mode RO is invalid") {
    val f = new Fixture
    val container = f.validDockerContainer.copy(
      volumes = Seq(f.validPersistentVolume.copy(mode = mesos.Volume.Mode.RO))
    )
    assert(validate(container).isFailure)
  }

  test("persistent volume with size 0 is invalid") {
    val f = new Fixture
    val container = f.validDockerContainer.copy(
      volumes = Seq(f.validPersistentVolume.copy(persistent = PersistentVolumeInfo(0)))
    )
    assert(validate(container).isFailure)
  }

  test("persistent volume with size < 0 is invalid") {
    val f = new Fixture
    val container = f.validDockerContainer.copy(
      volumes = Seq(f.validPersistentVolume.copy(persistent = PersistentVolumeInfo(-1)))
    )
    assert(validate(container).isFailure)
  }

}
