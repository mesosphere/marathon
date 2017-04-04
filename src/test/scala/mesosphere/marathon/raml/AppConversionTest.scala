package mesosphere.marathon
package raml

import mesosphere.marathon.api.v2.{ AppNormalization, AppsResource }
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.core.pod.{ BridgeNetwork, HostNetwork }
import mesosphere.marathon.state._
import mesosphere.{ UnitTest, ValidationTestLike }
import org.apache.mesos.{ Protos => Mesos }
import play.api.libs.json.Json

class AppConversionTest extends UnitTest with ValidationTestLike {
  private lazy val dockerBridgeApp = {
    val constraint = Protos.Constraint.newBuilder()
      .setField("foo")
      .setOperator(Protos.Constraint.Operator.CLUSTER)
      .setValue("1")
      .build()

    AppDefinition(
      id = PathId("/docker-bridge-app"),
      cmd = Some("test"),
      user = Some("user"),
      env = Map("A" -> state.EnvVarString("test"), "password" -> state.EnvVarSecretRef("secret0")),
      instances = 23,
      resources = Resources(),
      executor = "executor",
      constraints = Set(constraint),
      fetch = Seq(FetchUri("http://test.this")),
      backoffStrategy = BackoffStrategy(),
      container = Some(state.Container.Docker(
        volumes = Seq(state.DockerVolume("/container", "/host", Mesos.Volume.Mode.RW)),
        image = "foo/bla",
        portMappings = Seq(state.Container.PortMapping(12, name = Some("http-api"), hostPort = Some(23), servicePort = 123)),
        privileged = true
      )),
      networks = Seq(BridgeNetwork()),
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference.ByIndex(0)))),
      readinessChecks = Seq(core.readiness.ReadinessCheck()),
      acceptedResourceRoles = Set("*"),
      killSelection = state.KillSelection.OldestFirst,
      secrets = Map("secret0" -> state.Secret("/path/to/secret"))
    )
  }
  private lazy val hostApp = AppDefinition(
    id = PathId("/host-app"),
    networks = Seq(HostNetwork),
    cmd = Option("whatever"),
    requirePorts = true,
    portDefinitions = state.PortDefinitions(1, 2, 3),
    unreachableStrategy = state.UnreachableDisabled
  )

  def convertToRamlAndBack(app: AppDefinition): Unit = {
    s"app ${app.id.toString} is written to json and can be read again via formats" in {
      Given("An app")
      val ramlApp = app.toRaml[App]

      When("The app is translated to json and read back from formats")
      val json = Json.toJson(ramlApp)
      val features = Set(Features.SECRETS)
      val readApp: AppDefinition = withValidationClue {
        Raml.fromRaml(
          AppsResource.appNormalization(
            AppsResource.NormalizationConfig(features, AppNormalization.Configure(None))).normalized(ramlApp)
        )
      }
      Then("The app is identical")
      readApp should be(app)
    }
  }

  def convertToProtobufThenToRAML(app: AppDefinition): Unit = {
    s"app ${app.id.toString} is written as protobuf then converted to RAML matches directly converting the app to RAML" in {
      Given("A RAML app")
      val ramlApp = app.toRaml[App]

      When("The app is translated to proto, then to RAML")
      val protoRamlApp = app.toProto.toRaml[App]

      Then("The direct and indirect RAML conversions are identical")
      val normalizedProtoRamlApp = AppNormalization(
        AppNormalization.Configure(None)).normalized(AppNormalization.forDeprecated.normalized(protoRamlApp))
      normalizedProtoRamlApp should be(ramlApp)
    }
  }

  "AppConversion" should {
    behave like convertToRamlAndBack(dockerBridgeApp)
    behave like convertToProtobufThenToRAML(dockerBridgeApp)

    behave like convertToRamlAndBack(hostApp)
    behave like convertToProtobufThenToRAML(hostApp)

    "convert legacy service definitions to RAML" in {
      val legacy = Protos.ServiceDefinition.newBuilder()
        .setId("/legacy")
        .setCmd(Mesos.CommandInfo.newBuilder().setValue("sleep 60"))
        .setOBSOLETEIpAddress(Protos.ObsoleteIpAddress.newBuilder()
          .setNetworkName("fubar")
          .addLabels(Mesos.Label.newBuilder().setKey("try").setValue("me"))
          .addGroups("group1").addGroups("group2")
          .setDiscoveryInfo(Protos.ObsoleteDiscoveryInfo.newBuilder()
            .addPorts(Mesos.Port.newBuilder()
              .setNumber(234)
              .setName("port1")
              .setProtocol("udp")
            )
          )
        )
        .setResidency(Protos.ResidencyDefinition.newBuilder()
          .setRelaunchEscalationTimeoutSeconds(33)
          .setTaskLostBehavior(Protos.ResidencyDefinition.TaskLostBehavior.RELAUNCH_AFTER_TIMEOUT)
        )
        .setLastScalingAt(0)
        .setLastConfigChangeAt(0)
        .setExecutor("//cmd")
        .setInstances(2)
        .build
      val expectedRaml = App(
        id = "/legacy",
        cmd = Option("sleep 60"),
        executor = "//cmd",
        instances = 2,
        ipAddress = Option(IpAddress(
          discovery = Option(IpDiscovery(
            ports = Seq(
              IpDiscoveryPort(234, "port1", NetworkProtocol.Udp)
            )
          )),
          groups = Set("group1", "group2"),
          labels = Map("try" -> "me"),
          networkName = Option("fubar")
        )),
        residency = Option(AppResidency(
          relaunchEscalationTimeoutSeconds = 33,
          taskLostBehavior = TaskLostBehavior.RelaunchAfterTimeout
        )),
        versionInfo = Option(VersionInfo(
          lastScalingAt = Timestamp.zero.toOffsetDateTime,
          lastConfigChangeAt = Timestamp.zero.toOffsetDateTime
        )),
        portDefinitions = None
      )
      legacy.toRaml[App] should be(expectedRaml)
    }
  }
}
