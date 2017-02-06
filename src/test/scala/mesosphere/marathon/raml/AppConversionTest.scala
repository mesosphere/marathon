package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.state.{ AppDefinition, BackoffStrategy, FetchUri, PathId }
import org.apache.mesos.{ Protos => Mesos }
import play.api.libs.json.{ JsObject, Json }

class AppConversionTest extends UnitTest {
  "AppConversion" should {
    "An app is written to json and can be read again via formats" in {
      Given("An app")
      val constraint = Protos.Constraint.newBuilder().setField("foo").setOperator(Protos.Constraint.Operator.CLUSTER).build()
      val app = AppDefinition(
        id = PathId("/test"),
        cmd = Some("test"),
        user = Some("user"),
        env = Map("A" -> state.EnvVarString("test")),
        instances = 23,
        resources = Resources(),
        executor = "executor",
        constraints = Set(constraint),
        fetch = Seq(FetchUri("http://test.this")),
        portDefinitions = Seq(state.PortDefinition(123), state.PortDefinition(234)),
        requirePorts = true,
        backoffStrategy = BackoffStrategy(),
        container = Some(state.Container.Docker(
          volumes = Seq(state.DockerVolume("/container", "/host", Mesos.Volume.Mode.RW)),
          image = "foo/bla",
          network = Some(Mesos.ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = Seq(state.Container.PortMapping(12, Some(23), 123)),
          privileged = true
        )),
        healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference.ByIndex(0)))),
        readinessChecks = Seq(core.readiness.ReadinessCheck()),
        acceptedResourceRoles = Set("*")
      )

      When("The app is translated to json and read back from formats")
      val json = Json.toJson(app.toRaml[App])
      //filter out values that are written, but are not expected to read back
      //TODO: filtering is not needed, once we have the raml reads functionality
      val doNotRender = Set("uris", "ports", "version", "versionInfo")
      val cleanJson = JsObject(json.as[JsObject].value.filterKeys(field => !doNotRender(field)).toSeq)
      val readApp = cleanJson.as[AppDefinition](Formats.AppDefinitionReads).copy(versionInfo = app.versionInfo)

      Then("The app is identical")
      readApp should be(app)
    }
  }
}

