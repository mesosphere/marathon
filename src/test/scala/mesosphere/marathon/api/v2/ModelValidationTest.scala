package mesosphere.marathon.api.v2

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v2.json.{ GroupUpdate, GroupUpdate$ }
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.Container._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
import org.scalatest.{ BeforeAndAfterAll, Matchers, OptionValues }

import mesosphere.marathon.api.v2.Validation._

import scala.collection.immutable.Seq

class ModelValidationTest
    extends MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with OptionValues {

  test("A group update should pass validation") {
    val update = GroupUpdate(id = Some("/a/b/c".toPath))

    validate(update).isSuccess should be(true)
  }

  test("A group can not be updated to have more than the configured number of apps") {
    val group = Group("/".toPath, Set(
      createServicePortApp("/a".toPath, 0),
      createServicePortApp("/b".toPath, 0),
      createServicePortApp("/c".toPath, 0)
    ))

    val failedResult = Group.groupWithConfigValidator(Some(2)).apply(group)
    failedResult.isFailure should be(true)
    ValidationHelper.getAllRuleConstrains(failedResult)
      .find(v => v.message.contains("This Marathon instance may only handle up to 2 Apps!")) should be ('defined)

    val successfulResult = Group.groupWithConfigValidator(Some(10)).apply(group)
    successfulResult.isSuccess should be(true)
  }

  test("Model validation should catch new apps that conflict with service ports in existing apps") {
    val existingApp = createServicePortApp("/app1".toPath, 3200)
    val conflictingApp = createServicePortApp("/app2".toPath, 3200)

    val group = Group(id = PathId.empty, apps = Set(existingApp, conflictingApp))
    val result = validate(group)

    ValidationHelper.getAllRuleConstrains(result).exists(v =>
      v.message == "Requested service port 3200 conflicts with a service port in app /app2") should be(true)
  }

  test("Model validation should allow new apps that do not conflict with service ports in existing apps") {

    val existingApp = createServicePortApp("/app1".toPath, 3200)
    val conflictingApp = createServicePortApp("/app2".toPath, 3201)

    val group = Group(id = PathId.empty, apps = Set(existingApp, conflictingApp))
    val result = validate(group)

    result.isSuccess should be(true)
  }

  test("Model validation should check for application conflicts") {
    val existingApp = createServicePortApp("/app1".toPath, 3200)
    val conflictingApp = existingApp.copy(id = "/app2".toPath)

    val group = Group(id = PathId.empty, apps = Set(existingApp, conflictingApp))
    val result = validate(group)

    ValidationHelper.getAllRuleConstrains(result).exists(v =>
      v.message == "Requested service port 3200 conflicts with a service port in app /app2") should be(true)
  }

  private def createServicePortApp(id: PathId, servicePort: Int) =
    AppDefinition(
      id,
      container = Some(Container(
        docker = Some(Docker(
          image = "demothing",
          network = Some(Network.BRIDGE),
          portMappings = Some(Seq(PortMapping(2000, servicePort = servicePort)))
        ))
      ))
    )

}