package mesosphere.marathon
package api.v2

import java.util

import javax.ws.rs.core.Response
import akka.Done
import mesosphere.AkkaUnitTest
import mesosphere.marathon.api._
import mesosphere.marathon.api.v2.validation.NetworkValidationMessages
import mesosphere.marathon.core.appinfo.AppInfo.Embed
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.ContainerNetwork
import mesosphere.marathon.plugin.auth.{Authenticator, Authorizer}
import mesosphere.marathon.raml.{
  App,
  AppPersistentVolume,
  AppSecretVolume,
  AppUpdate,
  ContainerPortMapping,
  DockerContainer,
  DockerNetwork,
  DockerPullConfig,
  EngineType,
  EnvVarSecret,
  EnvVarValueOrSecret,
  IpAddress,
  IpDiscovery,
  IpDiscoveryPort,
  LinuxInfo,
  Network,
  NetworkMode,
  Raml,
  ReadMode,
  Seccomp,
  SecretDef,
  Container => RamlContainer
}
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.test.{JerseyTest, SettableClock}
import org.mockito.Matchers
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class AppsResourceTest extends AkkaUnitTest with JerseyTest {

  case class Fixture(
      clock: SettableClock = new SettableClock(),
      auth: TestAuthFixture = new TestAuthFixture,
      appTaskResource: AppTasksResource = mock[AppTasksResource],
      service: MarathonSchedulerService = mock[MarathonSchedulerService],
      appInfoService: AppInfoService = mock[AppInfoService],
      configArgs: Seq[String] = Seq("--enable_features", "external_volumes"),
      groupManager: GroupManager = mock[GroupManager]
  ) {
    val config: AllConf = AllConf.withTestConfig(configArgs: _*)
    val appsResource: AppsResource = new AppsResource(
      clock,
      system.eventStream,
      appTaskResource,
      service,
      appInfoService,
      config,
      groupManager,
      PluginManager.None
    )(auth.auth, auth.auth, ctx)

    implicit val authenticator: Authenticator = auth.auth
    implicit val authorizer: Authorizer = auth.auth

    val normalizationConfig = AppNormalization.Configuration(config, config.mesosRole())
    implicit lazy val appDefinitionValidator =
      AppDefinition.validAppDefinition(config.availableFeatures, ValidationHelper.roleSettings())(PluginManager.None)

    implicit val validateAndNormalizeApp: Normalization[raml.App] =
      AppHelpers.appNormalization(normalizationConfig, Set(ResourceRole.Unreserved))(AppNormalization.withCanonizedIds())

    implicit val normalizeAppUpdate: Normalization[raml.AppUpdate] =
      AppHelpers.appUpdateNormalization(normalizationConfig)(AppNormalization.withCanonizedIds())

    def normalize(app: App): App = {
      val migrated = AppNormalization.forDeprecated(normalizationConfig).normalized(app)
      AppNormalization(normalizationConfig).normalized(migrated)
    }

    def prepareApp(app: App, groupManager: GroupManager): (Array[Byte], DeploymentPlan) = {
      val normed = normalize(app)
      val appDef = Raml.fromRaml(normed)
      val rootGroup = Builders.newRootGroup(apps = Seq(appDef), newGroupEnforceRoleBehavior = config.newGroupEnforceRole())
      val plan = DeploymentPlan(rootGroup, rootGroup)
      val body = Json.stringify(Json.toJson(normed)).getBytes("UTF-8")
      groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
      groupManager.rootGroup() returns rootGroup
      groupManager.app(appDef.id) returns Some(appDef)
      (body, plan)
    }

    def prepareAppInGroup(app: App, groupManager: GroupManager): (Array[Byte], DeploymentPlan) = {
      val normed = normalize(app)
      val appDef = Raml.fromRaml(normed)

      val rootGroup = Builders.newRootGroup(apps = Seq(appDef), newGroupEnforceRoleBehavior = config.newGroupEnforceRole())
      val plan = DeploymentPlan(rootGroup, rootGroup)
      val body = Json.stringify(Json.toJson(normed)).getBytes("UTF-8")
      groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
      groupManager.rootGroup() returns rootGroup
      groupManager.app(appDef.id) returns Some(appDef)
      (body, plan)
    }

    def prepareGroup(
        groupId: String,
        groupManager: GroupManager,
        newGroupEnforceRoleBehavior: NewGroupEnforceRoleBehavior = NewGroupEnforceRoleBehavior.Off
    ) = {
      val groupPath = AbsolutePathId(groupId)

      val rootGroup = Builders.newRootGroup(groupIds = Set(groupPath), newGroupEnforceRoleBehavior = newGroupEnforceRoleBehavior)
      val plan = DeploymentPlan(rootGroup, rootGroup)
      groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
      groupManager.rootGroup() returns rootGroup
    }

    def createAppWithVolumes(
        `type`: String,
        volumes: String,
        groupManager: GroupManager,
        appsResource: AppsResource,
        auth: TestAuthFixture
    ): Response = {
      val app = App(id = "/app", cmd = Some("foo"))

      prepareApp(app, groupManager)

      val docker =
        if (`type` == "DOCKER")
          """"docker": {"image": "fop"},"""
        else ""
      val body =
        s"""
           |{
           |  "id": "external1",
           |  "cmd": "sleep 100",
           |  "instances": 1,
           |  "upgradeStrategy": { "minimumHealthCapacity": 0, "maximumOverCapacity": 0 },
           |  "container": {
           |    "type": "${`type`}",
           | $docker
           |    $volumes
           |  }
           |}
      """.stripMargin

      When("The request is processed")

      asyncRequest { r =>
        appsResource.create(body.getBytes("UTF-8"), force = false, auth.request, r)
      }
    }
  }

  case class FixtureWithRealGroupManager(
      initialRoot: Group = Group.empty("/".toAbsolutePath),
      clock: SettableClock = new SettableClock(),
      auth: TestAuthFixture = new TestAuthFixture,
      appTaskResource: AppTasksResource = mock[AppTasksResource],
      appInfoService: AppInfoService = mock[AppInfoService],
      configArgs: Seq[String] = Seq("--enable_features", "external_volumes")
  ) {

    val config: AllConf = AllConf.withTestConfig(configArgs: _*)
    val groupManagerFixture: TestGroupManagerFixture = new TestGroupManagerFixture(
      initialRoot = RootGroup.fromGroup(initialRoot, RootGroup.NewGroupStrategy.UsingConfig(config.newGroupEnforceRole()))
    )
    val groupManager: GroupManager = groupManagerFixture.groupManager
    val groupRepository: GroupRepository = groupManagerFixture.groupRepository
    val service = groupManagerFixture.service

    val appsResource: AppsResource = new AppsResource(
      clock,
      system.eventStream,
      appTaskResource,
      service,
      appInfoService,
      config,
      groupManager,
      PluginManager.None
    )(auth.auth, auth.auth, ctx)
  }

  "Apps Resource" should {
    "Create a new app successfully" in new Fixture {
      Given("An app and group")
      val app = App(id = "/app", cmd = Some("cmd"))
      val (body, plan) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val result = Try {
        val response = asyncRequest { r =>
          appsResource.create(body, force = false, auth.request, r)
        }

        Then("It is successful")
        response.getStatus should be(201)
        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

        And("the JSON is as expected, including a newly generated version")
        val expected = raml.AppInfo.fromParent(
          // TODO: The raml conversion sets the upgrade startegy to 1, 1. This should probably happen during the normalization
          parent = normalize(app)
            .copy(version = Some(clock.now().toOffsetDateTime), role = Some("*"), upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))),
          tasks = None,
          tasksStaged = Some(0),
          tasksRunning = Some(0),
          tasksUnhealthy = Some(0),
          tasksHealthy = Some(0),
          deployments = Some(Seq(raml.Identifiable(plan.id)))
        )
        JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
      }
      if (!result.isSuccess) {
        result.failed.foreach {
          case v: ValidationFailedException =>
            assert(result.isSuccess, s"JSON body = ${new String(body)} :: violations = ${v.failure.violations}")
          case th =>
            throw th
        }
      }
    }

    "Create a new app with w/ Mesos containerizer and a Docker config.json" in new Fixture(
      configArgs = Seq("--enable_features", "secrets")
    ) {
      Given("An app with a Docker config.json")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image", pullConfig = Some(DockerPullConfig("pullConfigSecret"))))
      )
      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container), secrets = Map("pullConfigSecret" -> SecretDef("/config")))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }
      Try(prepareApp(app, groupManager))

      Then("It is successful")
      assert(response.getStatus == 201, s"body=${new String(body)}, response=${response.getEntity.toString}")
    }

    "Creating a new app with w/ Docker containerizer and a Docker config.json should fail" in new Fixture(
      configArgs = Seq("--enable_features", "secrets")
    ) {
      Given("An app with a Docker config.json")
      val container = RamlContainer(
        `type` = EngineType.Docker,
        docker = Some(DockerContainer(image = "private/image", pullConfig = Option(DockerPullConfig("pullConfigSecret"))))
      )
      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container), secrets = Map("pullConfigSecret" -> SecretDef("/config")))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }
      Try(prepareApp(app, groupManager))

      Then("It fails")
      assert(response.getStatus == 422, s"body=${new String(body)}, response=${response.getEntity.asInstanceOf[String]}")
      response.getEntity.toString should include("pullConfig is not supported with Docker containerizer")
    }

    "Creating a new app with non-existing Docker config.json secret should fail" in new Fixture(
      configArgs = Seq("--enable_features", "secrets")
    ) {
      Given("An app with a Docker config.json")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image", pullConfig = Option(DockerPullConfig("pullConfigSecret"))))
      )
      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }
      Try(prepareApp(app, groupManager))

      Then("It fails")
      assert(response.getStatus == 422, s"body=${new String(body)}, response=${response.getEntity.asInstanceOf[String]}")
      response.getEntity.toString should include("pullConfig.secret must refer to an existing secret")
    }

    "Creation of an app with valid pull config should fail if secrets feature is disabled" in new Fixture {
      Given("An app with a Docker config.json")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image", pullConfig = Option(DockerPullConfig("pullConfigSecret"))))
      )
      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }
      Try(prepareApp(app, groupManager))

      Then("It is successful")
      response.getStatus should be(422) withClue s"body=${new String(body)}, response=${response.getEntity.asInstanceOf[String]}"

      val responseStr = response.getEntity.toString
      responseStr should include("/container/docker/pullConfig")
      responseStr should include("must be empty")
      responseStr should include("Feature secrets is not enabled. Enable with --enable_features secrets)")
    }

    "Accept an app definition with seccomp profile defined and unconfined = false" in new Fixture {
      Given("an app with seccomp profile defined and unconfined = false")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image")),
        linuxInfo = Some(
          LinuxInfo(
            seccomp = Some(
              Seccomp(
                profileName = Some("foo"),
                unconfined = false
              )
            )
          )
        )
      )

      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus shouldBe 201
    }

    "Accept an app definition WITHOUT seccomp profile and unconfined = true" in new Fixture {
      Given("an app with seccomp profile defined and unconfined = true")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image")),
        linuxInfo = Some(
          LinuxInfo(
            seccomp = Some(
              Seccomp(
                unconfined = true
              )
            )
          )
        )
      )

      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus shouldBe 201
    }

    "Decline an app definition with seccomp profiled defined and unconfined = true" in new Fixture {
      Given("an app with seccomp profile defined and unconfined = true")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image")),
        linuxInfo = Some(
          LinuxInfo(
            seccomp = Some(
              Seccomp(
                profileName = Some("foo"),
                unconfined = true
              )
            )
          )
        )
      )

      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container))

      When("The create request is made")
      val body = Json.stringify(Json.toJson(app)).getBytes("UTF-8")
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It fails")
      withClue(s"body=${new String(body)}, response=${response.getEntity.asInstanceOf[String]}") {
        response.getStatus shouldBe 422
        response.getEntity.toString should include("Seccomp unconfined can NOT be true when Profile is defined")
      }
    }

    "Decline an app definition WITHOUT seccomp profiled defined and unconfined = false" in new Fixture {
      Given("an app without seccomp profile defined and unconfined = false")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image")),
        linuxInfo = Some(
          LinuxInfo(
            seccomp = Some(
              Seccomp(
                unconfined = false
              )
            )
          )
        )
      )

      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container))

      When("The create request is made")
      val body = Json.stringify(Json.toJson(app)).getBytes("UTF-8")
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It fails")
      withClue(s"body=${new String(body)}, response=${response.getEntity.asInstanceOf[String]}") {
        response.getStatus shouldBe 422
        response.getEntity.toString should include("Seccomp unconfined must be true when Profile is NOT defined")
      }
    }

    "Accept an app definition with ipcMode defined private and shmSize set" in new Fixture {
      Given("an app with ipcMode defined private and shmSize set")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image")),
        linuxInfo = Some(
          LinuxInfo(
            ipcInfo = Some(
              raml.IPCInfo(
                mode = raml.IPCMode.Private,
                shmSize = Some(16)
              )
            )
          )
        )
      )

      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus shouldBe 201
    }

    "Accept an app definition with ipcMode defined private and shmSize NOT set" in new Fixture {
      Given("an app with ipcMode defined private and shmSize set")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image")),
        linuxInfo = Some(
          LinuxInfo(
            ipcInfo = Some(
              raml.IPCInfo(
                mode = raml.IPCMode.Private
              )
            )
          )
        )
      )

      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus shouldBe 201
    }

    "Decline an app definition with ipcMode defined sharedParent and shmSize set" in new Fixture {
      Given("an app with ipcMode defined sharedParent and shmSize set")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image")),
        linuxInfo = Some(
          LinuxInfo(
            ipcInfo = Some(
              raml.IPCInfo(
                mode = raml.IPCMode.ShareParent,
                shmSize = Some(16)
              )
            )
          )
        )
      )

      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container))

      When("The create request is made")
      val body = Json.stringify(Json.toJson(app)).getBytes("UTF-8")
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It fails")
      withClue(s"body=${new String(body)}, response=${response.getEntity.asInstanceOf[String]}") {
        response.getStatus shouldBe 422
        response.getEntity.toString should include("ipcInfo shmSize can NOT be set when mode is SHARE_PARENT")
      }
    }

    "Accept an app definition with ipcMode defined sharedParent and shmSize NOT set" in new Fixture {
      Given("an app with ipcMode defined sharedParent and shmSize NOT set")
      val container = RamlContainer(
        `type` = EngineType.Mesos,
        docker = Some(DockerContainer(image = "private/image")),
        linuxInfo = Some(
          LinuxInfo(
            ipcInfo = Some(
              raml.IPCInfo(
                mode = raml.IPCMode.ShareParent
              )
            )
          )
        )
      )

      val app = App(id = "/app", cmd = Some("cmd"), container = Some(container))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus shouldBe 201
    }

    "Do partial update with patch methods" in new Fixture {
      Given("An app")
      val id = "/app"
      val app = App(
        id = id,
        cmd = Some("cmd"),
        instances = 1
      )
      prepareApp(app, groupManager) // app is stored

      When("The application is updated")
      val updateRequest = App(id = id, instances = 2)
      val updatedBody = Json.stringify(Json.toJson(updateRequest)).getBytes("UTF-8")
      val response = asyncRequest { r =>
        appsResource.patch(app.id, updatedBody, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus should be(200)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Fail creating application when network name is missing" in new Fixture {
      Given("An app and group")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        networks = Seq(Network(mode = NetworkMode.Container))
      )

      When("The create request is made")
      val response = asyncRequest { r =>
        appsResource.create(Json.stringify(Json.toJson(app)).getBytes("UTF-8"), force = false, auth.request, r)
      }

      Then("Validation fails")
      response.getStatus should be(422)
      response.getEntity.toString should include(NetworkValidationMessages.NetworkNameMustBeSpecified)
    }

    "Create a new app with IP/CT, no default network name, Alice does not specify a network" in new Fixture {
      Given("An app and group")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        networks = Seq(Network(mode = NetworkMode.Container))
      )
      the[NormalizationException] thrownBy {
        prepareApp(app, groupManager)
      } should have message NetworkNormalizationMessages.ContainerNetworkNameUnresolved
    }
    "Create a new app with IP/CT on virtual network foo" in new Fixture {
      Given("An app and group")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        networks = Seq(Network(mode = NetworkMode.Container, name = Some("foo")))
      )
      val (body, plan) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      assert(response.getStatus == 201, s"body = ${new String(body)}, response = ${response.getEntity.toString}")
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      And("the JSON is as expected, including a newly generated version")
      val expected = raml.AppInfo.fromParent(
        parent = normalize(app).copy(
          version = Some(clock.now().toOffsetDateTime),
          role = Some(ResourceRole.Unreserved),
          upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))
        ),
        tasks = None,
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasksHealthy = Some(0),
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )
      JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
    }

    "Create a new app with IP/CT on virtual network foo w/ MESOS container spec" in new Fixture {
      Given("An app and group")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        networks = Seq(Network(mode = NetworkMode.Container, name = Some("foo"))),
        container = Some(raml.Container(`type` = EngineType.Mesos))
      )
      val (body, plan) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      And("the JSON is as expected, including a newly generated version")
      val expected = raml.AppInfo.fromParent(
        parent = normalize(app).copy(
          version = Some(clock.now().toOffsetDateTime),
          role = Some(ResourceRole.Unreserved),
          upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))
        ),
        tasks = None,
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasksHealthy = Some(0),
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )
      JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
    }

    "Create a new app with IP/CT on virtual network foo, then update it to bar" in new Fixture {
      Given("An app and group")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        networks = Seq(Network(mode = NetworkMode.Container, name = Some("foo")))
      )
      prepareApp(app, groupManager)

      When("The application is updated")
      val updatedApp = app.copy(networks = Seq(Network(mode = NetworkMode.Container, name = Some("bar"))))
      val updatedJson = Json.toJson(updatedApp).as[JsObject]
      val updatedBody = Json.stringify(updatedJson).getBytes("UTF-8")
      val response = asyncRequest { r =>
        appsResource.replace(updatedApp.id, updatedBody, force = false, partialUpdate = true, auth.request, r)
      }

      Then("It is successful")
      assert(response.getStatus == 200, s"response=${response.getEntity.toString}")
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Create a new app with IP/CT on virtual network foo, then update it to nothing" in new FixtureWithRealGroupManager(
      initialRoot =
        Builders.newRootGroup(apps = Seq(Builders.newAppDefinition.command("/app".toAbsolutePath, networks = Seq(ContainerNetwork("foo")))))
    ) {
      Given("An app and group")
      val updatedApp = App(
        id = "/app",
        cmd = Some("cmd"),
        networks = Seq(Network(mode = NetworkMode.Container))
      )

      When("The application is updated")
      val updatedJson = Json.toJson(updatedApp).as[JsObject]
      val updatedBody = Json.stringify(updatedJson).getBytes("UTF-8")
      val response = asyncRequest { r =>
        appsResource.replace(updatedApp.id, updatedBody, force = false, partialUpdate = false, auth.request, r)
      }

      Then("the update should fail")
      response.getStatus should be(422)
      response.getEntity.toString should include(NetworkValidationMessages.NetworkNameMustBeSpecified)
    }

    "Create a new app without IP/CT when default virtual network is bar" in new Fixture(configArgs = Seq("--default_network_name", "bar")) {
      Given("An app and group")

      val app = App(
        id = "/app",
        cmd = Some("cmd")
      )
      val (body, plan) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      assert(response.getStatus == 201, s"body=${new String(body)}, response=${response.getEntity.toString}")
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      And("the JSON is as expected, including a newly generated version")
      val expected = raml.AppInfo.fromParent(
        parent = normalize(app).copy(
          version = Some(clock.now().toOffsetDateTime),
          role = Some(ResourceRole.Unreserved),
          upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))
        ),
        tasks = None,
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasksHealthy = Some(0),
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )
      JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
    }

    "Create a new app with IP/CT when default virtual network is bar, Alice did not specify network name" in new Fixture(
      configArgs = Seq("--default_network_name", "bar")
    ) {
      Given("An app and group")

      val app = App(id = "/app", cmd = Some("cmd"), networks = Seq(Network(mode = NetworkMode.Container)))
      val (body, plan) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      And("the JSON is as expected, including a newly generated version")
      val expected = raml.AppInfo.fromParent(
        parent = normalize(app).copy(
          version = Some(clock.now().toOffsetDateTime),
          role = Some(ResourceRole.Unreserved),
          networks = Seq(raml.Network(name = Some("bar"))),
          upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))
        ),
        tasks = None,
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasksHealthy = Some(0),
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )
      JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
    }

    "Create a new app with IP/CT when default virtual network is bar, but Alice specified foo" in new Fixture(
      configArgs = Seq("--default_network_name", "bar")
    ) {
      Given("An app and group")

      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        networks = Seq(Network(mode = NetworkMode.Container, name = Some("foo")))
      )
      val (body, plan) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      And("the JSON is as expected, including a newly generated version")
      val expected = raml.AppInfo.fromParent(
        parent = normalize(app).copy(
          version = Some(clock.now().toOffsetDateTime),
          role = Some(ResourceRole.Unreserved),
          upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))
        ),
        tasks = None,
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasksHealthy = Some(0),
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )
      JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
    }

    "Create a new app with IP/CT with virtual network foo w/ Docker" in new Fixture {
      // we intentionally use the deprecated API to ensure that we're more fully exercising normalization
      Given("An app and group")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        ipAddress = Some(IpAddress(networkName = Some("foo"))),
        container = Some(
          raml.Container(
            `type` = EngineType.Docker,
            docker = Some(
              DockerContainer(
                portMappings = Some(Seq(ContainerPortMapping(containerPort = 0))),
                image = "jdef/helpme",
                network = Some(DockerNetwork.User)
              )
            )
          )
        )
      )
      val (body, plan) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      And("the JSON is as expected, including a newly generated version")
      val expected = raml.AppInfo.fromParent(
        parent = normalize(app).copy(
          version = Some(clock.now().toOffsetDateTime),
          role = Some(ResourceRole.Unreserved),
          upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))
        ),
        tasks = None,
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasksHealthy = Some(0),
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )
      JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
    }

    "Create a new app in BRIDGE mode w/ Docker" in new Fixture {
      Given("An app and group")
      val container = DockerContainer(
        network = Some(DockerNetwork.Bridge),
        image = "jdef/helpme",
        portMappings = Some(
          Seq(
            ContainerPortMapping(containerPort = 0)
          )
        )
      )

      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        container = Some(raml.Container(`type` = EngineType.Docker, docker = Some(container))),
        portDefinitions = None
      )

      val appDef = Raml.fromRaml(normalize(app))
      val rootGroup = Builders.newRootGroup(apps = Seq(appDef))
      val plan = DeploymentPlan(rootGroup, rootGroup)
      val body = Json.stringify(Json.toJson(app).as[JsObject]).getBytes("UTF-8")
      groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
      groupManager.rootGroup() returns rootGroup
      groupManager.app(appDef.id) returns Some(appDef)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      And("the JSON is as expected, including a newly generated version")
      val containerDef = appDef.container
      val expected = raml.AppInfo.fromParent(
        parent = Raml.toRaml(
          appDef.copy(
            versionInfo = VersionInfo.OnlyVersion(clock.now()),
            role = ResourceRole.Unreserved,
            container = containerDef.map(
              _.copyWith(
                portMappings = Seq(
                  Container.PortMapping(containerPort = 0, hostPort = Some(0), protocol = "tcp")
                )
              )
            )
          )
        ),
        tasks = None,
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasksHealthy = Some(0),
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )
      JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
    }

    "Create a new app in USER mode w/ ipAddress.discoveryInfo w/ Docker" in new Fixture {
      Given("An app and group")
      val body =
        """
        | {
        |   "id": "/app",
        |   "cmd": "cmd",
        |   "ipAddress": {
        |     "networkName": "foo",
        |     "discovery": {
        |       "ports": [
        |         { "number": 1, "name": "bob", "protocol": "tcp" }
        |       ]
        |     }
        |   },
        |   "container": {
        |     "type": "DOCKER",
        |     "docker": {
        |       "image": "jdef/helpme",
        |       "portMappings": [
        |         { "containerPort": 0, "protocol": "tcp" }
        |       ]
        |     }
        |   },
        |   "portDefinitions": []
        | }
      """.stripMargin.getBytes

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is not successful")
      response.getStatus should be(422)
      response.getEntity.toString should include("ipAddress/discovery is not allowed for Docker containers")
    }

    "Create a new app in HOST mode w/ ipAddress.discoveryInfo w/ Docker" in new Fixture {
      Given("An app and group")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        ipAddress = Some(
          IpAddress(
            networkName = Some("foo"),
            discovery = Some(
              IpDiscovery(ports =
                Seq(
                  IpDiscoveryPort(number = 1, name = "bob")
                )
              )
            )
          )
        ),
        container = Some(
          raml.Container(
            `type` = EngineType.Docker,
            docker = Some(
              DockerContainer(
                network = Some(DockerNetwork.Host),
                image = "jdef/helpme"
              )
            )
          )
        ),
        role = Some("someRole")
      )
      // mixing ipAddress with Docker containers is not allowed by validation; API migration fails it too
      a[NormalizationException] shouldBe thrownBy(prepareApp(app, groupManager))
    }

    "Create a new app (that uses secret ref) successfully" in new Fixture(configArgs = Seq("--enable_features", Features.SECRETS)) {
      Given("The secrets feature is enabled")

      And("An app with a secret and an envvar secret-ref")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        secrets = Map[String, SecretDef]("foo" -> SecretDef("/bar")),
        env = Map[String, EnvVarValueOrSecret]("NAMED_FOO" -> raml.EnvVarSecret("foo"))
      )
      val (body, plan) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      And("the JSON is as expected, including a newly generated version")
      val expected = raml.AppInfo.fromParent(
        parent = normalize(app).copy(
          version = Some(clock.now().toOffsetDateTime),
          role = Some(ResourceRole.Unreserved),
          upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))
        ),
        tasks = None,
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasksHealthy = Some(0),
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )
      JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
    }

    "Create a new app (that uses undefined secret ref) and fails" in new Fixture(configArgs = Seq("--enable_features", Features.SECRETS)) {
      Given("The secrets feature is enabled")

      And("An app with an envvar secret-ref that does not point to an undefined secret")
      val app = App(id = "/app", cmd = Some("cmd"), env = Map[String, EnvVarValueOrSecret]("NAMED_FOO" -> raml.EnvVarSecret("foo")))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It fails")
      response.getStatus should be(422)
      response.getEntity.toString should include("/env/NAMED_FOO/secret")
      response.getEntity.toString should include("references an undefined secret")
    }

    "Create a new app (that uses file based secret) successfully" in new Fixture(configArgs = Seq("--enable_features", Features.SECRETS)) {
      Given("The secrets feature is enabled")

      And("An app with a secret and an envvar secret-ref")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        secrets = Map[String, SecretDef]("foo" -> SecretDef("/bar")),
        container = Some(raml.Container(`type` = EngineType.Mesos, volumes = Seq(AppSecretVolume("/path", "foo"))))
      )
      val (body, plan) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      And("the JSON is as expected, including a newly generated version")
      val expected = raml.AppInfo.fromParent(
        parent = normalize(app).copy(
          version = Some(clock.now().toOffsetDateTime),
          role = Some(ResourceRole.Unreserved),
          upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))
        ),
        tasks = None,
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasksHealthy = Some(0),
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )
      JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
    }

    "The secrets feature is NOT enabled and create app (that uses secret refs) fails" in new Fixture(configArgs = Seq()) {
      Given("The secrets feature is NOT enabled")

      config.isFeatureSet(Features.SECRETS) should be(false)

      And("An app with an envvar secret-ref that does not point to an undefined secret")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        secrets = Map[String, SecretDef]("foo" -> SecretDef("/bar")),
        env = Map[String, EnvVarValueOrSecret]("NAMED_FOO" -> raml.EnvVarSecret("foo"))
      )
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It fails")
      response.getStatus should be(422)
      response.getEntity.toString should include("Feature secrets is not enabled")
    }

    "The secrets feature is NOT enabled and create app (that uses file base secrets) fails" in new Fixture(configArgs = Seq()) {
      Given("The secrets feature is NOT enabled")

      config.isFeatureSet(Features.SECRETS) should be(false)

      And("An app with an envvar secret-def")
      val secretVolume = AppSecretVolume("/path", "bar")
      val containers = raml.Container(`type` = EngineType.Mesos, volumes = Seq(secretVolume))
      val app = App(id = "/app", cmd = Some("cmd"), container = Some(containers), secrets = Map("bar" -> SecretDef("foo")))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It fails")
      response.getStatus should be(422)
      response.getEntity.toString should include("Feature secrets is not enabled.")
    }

    "Create a new app fails with Validation errors for negative resources" in new Fixture {
      Given("An app with negative resources")

      {
        val app = App(id = "/app", cmd = Some("cmd"), mem = -128)
        val (body, plan @ _) = prepareApp(app, groupManager)

        Then("A constraint violation exception is thrown")
        val response = asyncRequest { r =>
          appsResource.create(body, force = false, auth.request, r)
        }
        response.getStatus should be(422)
      }

      {
        val app = App(id = "/app", cmd = Some("cmd"), cpus = -1)
        val (body, _) = prepareApp(app, groupManager)

        val response = asyncRequest { r =>
          appsResource.create(body, force = false, auth.request, r)
        }
        response.getStatus should be(422)
      }

      {
        val app = App(id = "/app", cmd = Some("cmd"), instances = -1)
        val (body, _) = prepareApp(app, groupManager)

        val response = asyncRequest { r =>
          appsResource.create(body, force = false, auth.request, r)
        }
        response.getStatus should be(422)
      }

    }

    "Create a new app successfully using ports instead of portDefinitions" in new Fixture {
      Given("An app and group")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        portDefinitions = Some(raml.PortDefinitions(1000, 1001))
      )
      val (_, plan) = prepareApp(app, groupManager)
      val appJson = Json.toJson(app).as[JsObject]
      val body = Json.stringify(appJson).getBytes("UTF-8")

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      assert(response.getStatus == 201, s"body=${new String(body)}, response=${response.getEntity.toString}")

      And("the JSON is as expected, including a newly generated version")
      val expected = raml.AppInfo.fromParent(
        parent = normalize(app).copy(
          version = Some(clock.now().toOffsetDateTime),
          role = Some(ResourceRole.Unreserved),
          upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))
        ),
        tasks = None,
        tasksStaged = Some(0),
        tasksRunning = Some(0),
        tasksUnhealthy = Some(0),
        tasksHealthy = Some(0),
        deployments = Some(Seq(raml.Identifiable(plan.id)))
      )
      JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
    }

    "Create a new app fails with Validation errors" in new Fixture {
      Given("An app with validation errors")
      val app = App(id = "/app")
      val (body, _) = prepareApp(app, groupManager)

      Then("A constraint violation exception is thrown")
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }
      response.getStatus should be(422)
    }

    "Create a new app with float instance count fails" in new Fixture {
      Given("The json of an invalid application")
      val invalidAppJson = Json.stringify(Json.obj("id" -> "/foo", "cmd" -> "cmd", "instances" -> 0.1))
      val rootGroup = Builders.newRootGroup()
      val plan = DeploymentPlan(rootGroup, rootGroup)
      groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
      groupManager.rootGroup() returns rootGroup

      Then("A constraint violation exception is thrown")
      val body = invalidAppJson.getBytes("UTF-8")
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }
      response.getStatus should be(422)
    }

    "Replace an existing application" in new Fixture {
      Given("An app and group")
      val app = AppDefinition(id = AbsolutePathId("/app"), cmd = Some("foo"), role = "*")
      val rootGroup = Builders.newRootGroup(apps = Seq(app))
      val plan = DeploymentPlan(rootGroup, rootGroup)
      val body = """{ "cmd": "bla" }""".getBytes("UTF-8")
      groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
      groupManager.app(AbsolutePathId("/app")) returns Some(app)

      When("The application is updated")
      val response = asyncRequest { r =>
        appsResource.replace(app.id.toString, body, force = false, partialUpdate = true, auth.request, r)
      }

      Then("The application is updated")
      response.getStatus should be(200)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Replace an existing application using ports instead of portDefinitions" in new Fixture {
      Given("An app and group")
      val app = App(id = "/app", cmd = Some("foo"))
      prepareApp(app, groupManager)

      val appJson = Json.toJson(app).as[JsObject]
      val appJsonWithOnlyPorts = appJson +
        ("ports" -> Json.parse("""[1000, 1001]"""))
      val body = Json.stringify(appJsonWithOnlyPorts).getBytes("UTF-8")

      When("The application is updated")
      val response = asyncRequest { r =>
        appsResource.replace(app.id, body, force = false, partialUpdate = true, auth.request, r)
      }

      Then("The application is updated")
      response.getStatus should be(200)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Replace an existing application fails due to docker container validation" in new Fixture {
      Given("An app update with an invalid container (missing docker field)")
      val app = App(id = "/app", cmd = Some("foo"))
      prepareApp(app, groupManager)

      val body =
        """{
          |  "cmd": "sleep 1",
          |  "container": {
          |    "type": "DOCKER"
          |  }
          |}""".stripMargin.getBytes("UTF-8")

      Then("A validation exception is thrown")
      val response = asyncRequest { r =>
        appsResource.replace(app.id, body, force = false, partialUpdate = true, auth.request, r)
      }
      response.getStatus should be(422)
      response.getEntity.toString should include("/container/docker")
      response.getEntity.toString should include("not defined")
    }

    "Creating an app with broken volume definition fails with readable error message" in new Fixture {
      Given("An app update with an invalid volume (wrong field name)")
      val response = createAppWithVolumes(
        "MESOS",
        """
          |    "volumes": [{
          |      "containerPath": "var",
          |      "persistent_WRONG_FIELD_NAME": {
          |        "size": 10
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates that the hostPath of volumes[0] is missing")
      // although the wrong field should fail
      response.getStatus should be(422)
      response.getEntity.toString should include("/container/volumes(0)/hostPath")
      response.getEntity.toString should include("undefined")
    }

    "Creating an app with an external volume for an illegal provider should fail" in new Fixture {
      Given("An app invalid volume (illegal volume provider)")
      val response = createAppWithVolumes(
        "MESOS",
        """
          |    "volumes": [{
          |      "containerPath": "var",
          |      "external": {
          |        "size": 10,
          |        "name": "foo",
          |        "provider": "acme"
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates that the hostPath of volumes[0] is missing") // although the wrong field should fail
      response.getStatus should be(422)
      response.getEntity.toString should include("/container/volumes(0)/external/provider")
      response.getEntity.toString should include("is unknown provider")
    }

    "Creating an app with an external volume with no name or provider name specified should FAIL provider validation" in new Fixture {
      Given("An app with an unnamed volume provider")
      val response =
        createAppWithVolumes(
          "MESOS",
          """
          |    "volumes": [{
          |      "containerPath": "var",
          |      "external": {
          |        "size": 10
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
          groupManager,
          appsResource,
          auth
        )

      Then("The return code indicates create failure")
      response.getStatus should be(422)
      val responseBody = response.getEntity.toString
      responseBody should include("/container/volumes(0)/external/provider")
      responseBody should include("/container/volumes(0)/external/name")
    }

    "Creating an app with an external volume w/ MESOS and absolute containerPath should succeed validation" in new Fixture {
      Given("An app with a named, non-'agent' volume provider")
      val response = createAppWithVolumes(
        "MESOS",
        """
          |    "volumes": [{
          |      "containerPath": "/var",
          |      "external": {
          |        "size": 10,
          |        "provider": "dvdi",
          |        "name": "namedfoo",
          |        "options": {"dvdi/driver": "bar"}
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates create failure")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Creating an app with an external volume w/ MESOS and dotted containerPath should fail validation" in new Fixture {
      Given("An app with a named, non-'agent' volume provider")
      val response = createAppWithVolumes(
        "MESOS",
        """
          |    "volumes": [{
          |      "containerPath": ".",
          |      "external": {
          |        "size": 10,
          |        "provider": "dvdi",
          |        "name": "namedfoo",
          |        "options": {"dvdi/driver": "bar"}
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates create failure")
      response.getStatus should be(422)
      response.getEntity.toString should include("/container/volumes(0)/containerPath")
    }

    "Creating an app with an external volume w/ MESOS and nested containerPath should fail validation" in new Fixture {
      Given("An app with a named, non-'agent' volume provider")
      val response = createAppWithVolumes(
        "MESOS",
        """
          |    "volumes": [{
          |      "containerPath": "var/child",
          |      "external": {
          |        "size": 10,
          |        "provider": "dvdi",
          |        "name": "namedfoo",
          |        "options": {"dvdi/driver": "bar"}
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates create failure")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Creating an app with an external volume and MESOS containerizer should pass validation" in new Fixture {
      Given("An app with a named, non-'agent' volume provider")
      val response = createAppWithVolumes(
        "MESOS",
        """
          |    "volumes": [{
          |      "containerPath": "var",
          |      "external": {
          |        "size": 10,
          |        "provider": "dvdi",
          |        "name": "namedfoo",
          |        "options": {"dvdi/driver": "bar"}
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates create success")
      assert(response.getStatus == 201, s"response=${response.getEntity.toString}")
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Creating an app with an external volume using an invalid rexray option should fail" in new Fixture {
      Given("An app with a named, non-'agent' volume provider")
      val response = createAppWithVolumes(
        "MESOS",
        """
          |    "volumes": [{
          |      "containerPath": "var",
          |      "external": {
          |        "size": 10,
          |        "provider": "dvdi",
          |        "name": "namedfoo",
          |        "options": {"dvdi/driver": "rexray", "dvdi/iops": "0"}
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates validation error")
      response.getStatus should be(422)
      response.getEntity.toString should include("/container/volumes(0)/external/options/\\\"dvdi/iops\\\"")
    }

    "Creating an app with an external volume w/ relative containerPath DOCKER containerizer should succeed (available with Mesos 1.0)" in new Fixture {
      Given("An app with a named, non-'agent' volume provider")
      val response = createAppWithVolumes(
        "DOCKER",
        """
          |    "volumes": [{
          |      "containerPath": "relative/path",
          |      "external": {
          |        "provider": "dvdi",
          |        "name": "namedfoo",
          |        "options": {"dvdi/driver": "bar"}
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates create failed")
      response.getStatus should be(201)
    }

    "Creating an app with an external volume and DOCKER containerizer should pass validation" in new Fixture {
      Given("An app with a named, non-'agent' volume provider")
      val response = createAppWithVolumes(
        "DOCKER",
        """
          |    "volumes": [{
          |      "containerPath": "/var",
          |      "external": {
          |        "provider": "dvdi",
          |        "name": "namedfoo",
          |        "options": {"dvdi/driver": "bar"}
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates create success")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Creating a DOCKER app with an external volume without driver option should NOT pass validation" in new Fixture {
      Given("An app with a named, non-'agent' volume provider")
      val response =
        createAppWithVolumes(
          "DOCKER",
          """
            |    "volumes": [{
            |      "containerPath": "/var",
            |      "external": {
            |        "provider": "dvdi",
            |        "name": "namedfoo",
            |        "options": {}
            |      },
            |      "mode": "RW"
            |    }]
          """.stripMargin,
          groupManager,
          appsResource,
          auth
        )

      Then("The return code indicates create failure")
      response.getStatus should be(422)
      response.getEntity.toString should include("/container/volumes(0)/external/options(\\\"dvdi/driver\\\")")
      response.getEntity.toString should include("not defined")
    }

    "Creating a Docker app with an external volume with size should fail validation" in new Fixture {
      Given("An app with a named, non-'agent' volume provider")
      val response = createAppWithVolumes(
        "DOCKER",
        """
          |    "volumes": [{
          |      "containerPath": "/var",
          |      "external": {
          |        "provider": "dvdi",
          |        "name": "namedfoo",
          |        "size": 42,
          |        "options": {
          |           "dvdi/driver": "rexray"
          |        }
          |      },
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      withClue(response.getEntity.toString) {
        Then("The return code indicates a validation error")
        response.getStatus should be(422)
        response.getEntity.toString should include("/container/volumes(0)/external/size")
        response.getEntity.toString should include("must be undefined")
      }
    }
    "Creating an app with an external volume, and docker volume and DOCKER containerizer should pass validation" in new Fixture {
      Given("An app with a named, non-'agent' volume provider and a docker host volume")
      val response = createAppWithVolumes(
        "DOCKER",
        """
          |    "volumes": [{
          |      "containerPath": "/var",
          |      "external": {
          |        "provider": "dvdi",
          |        "name": "namedfoo",
          |        "options": {"dvdi/driver": "bar"}
          |      },
          |      "mode": "RW"
          |    },{
          |      "hostPath": "/ert",
          |      "containerPath": "/ert",
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates create success")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Creating an app with a duplicate external volume name (unfortunately) passes validation" in new Fixture {
      // we'll need to mitigate this with documentation: probably deprecating support for using
      // volume names with non-persistent volumes.
      Given("An app with DOCKER containerizer and multiple references to the same named volume")
      val response = createAppWithVolumes(
        "DOCKER",
        """
          |    "volumes": [{
          |      "containerPath": "/var",
          |      "external": {
          |        "provider": "dvdi",
          |        "name": "namedfoo",
          |        "options": {"dvdi/driver": "bar"}
          |      },
          |      "mode": "RW"
          |    },{
          |      "hostPath": "namedfoo",
          |      "containerPath": "/ert",
          |      "mode": "RW"
          |    }]
        """.stripMargin,
        groupManager,
        appsResource,
        auth
      )

      Then("The return code indicates create success")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Replacing an existing application with a Mesos docker container passes validation" in new Fixture {
      Given("An app update to a Mesos container with a docker image")
      val app = App(id = "/app", cmd = Some("foo"))
      prepareApp(app, groupManager)

      val body =
        """{
          |  "cmd": "sleep 1",
          |  "container": {
          |    "type": "MESOS",
          |    "docker": {
          |      "image": "/test:latest"
          |    }
          |  }
          |}""".stripMargin.getBytes("UTF-8")

      When("The application is updated")
      val response = asyncRequest { r =>
        appsResource.replace(app.id, body, force = false, partialUpdate = true, auth.request, r)
      }

      Then("The return code indicates success")
      response.getStatus should be(200)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Replacing an existing docker application, upgrading from host to user networking" in new Fixture {
      Given("a docker app using host networking and non-empty port definitions")
      val app = AppDefinition(
        id = AbsolutePathId("/app"),
        container = Some(Container.Docker(image = "foo")),
        portDefinitions = PortDefinitions(0),
        role = "*"
      )

      When("upgraded to user networking using full-replacement semantics (no port definitions)")
      val body =
        """{
          |  "cmd": "sleep 1",
          |  "container": {
          |    "type": "DOCKER",
          |    "docker": {
          |      "image": "/test:latest",
          |      "network": "USER"
          |    }
          |  },
          |  "ipAddress": { "networkName": "dcos" }
          |}""".stripMargin.getBytes("UTF-8")
      val appUpdate = appsResource.canonicalAppUpdateFromJson(app.id, body, CompleteReplacement, false)

      Then("the application is updated")
      implicit val identity = auth.identity
      val app1 = AppHelpers.updateOrCreate(
        app.id,
        Some(app),
        appUpdate,
        partialUpdate = false,
        allowCreation = true,
        now = clock.now(),
        service = service
      )

      And("also works when the update operation uses partial-update semantics, dropping portDefinitions")
      val partUpdate = appsResource.canonicalAppUpdateFromJson(app.id, body, PartialUpdate(app), false)
      val app2 = AppHelpers.updateOrCreate(
        app.id,
        Some(app),
        partUpdate,
        partialUpdate = true,
        allowCreation = false,
        now = clock.now(),
        service = service
      )

      app1 should be(app2)
    }

    "Restart an existing app" in new Fixture {
      val app = AppDefinition(id = AbsolutePathId("/app"), cmd = Some("sleep"), role = "*")
      val rootGroup = Builders.newRootGroup(apps = Seq(app))
      val plan = DeploymentPlan(rootGroup, rootGroup)
      service.deploy(any, any) returns Future.successful(Done)
      groupManager.app(AbsolutePathId("/app")) returns Some(app)

      groupManager.updateApp(any, any, any, any, any) returns Future.successful(plan)
      val response = asyncRequest { r =>
        appsResource.restart(app.id.toString, force = true, auth.request, r)
      }

      response.getStatus should be(200)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    }

    "Restart a non existing app will fail" in new Fixture {
      val missing = AbsolutePathId("/app")
      groupManager.app(AbsolutePathId("/app")) returns None
      groupManager.updateApp(any, any, any, any, any) returns Future.failed(AppNotFoundException(missing))

      val response = asyncRequest { r =>
        appsResource.restart(missing.toString, force = true, auth.request, r)
      }
      response.getStatus shouldBe 404
    }

    "Index has counts and deployments by default (regression for #2171)" in new Fixture {
      Given("An app and group")
      val app = raml.App(id = "/app", cmd = Some("foo"), role = Some("*"))
      val expectedEmbeds: Set[Embed] = Set(Embed.Counts, Embed.Deployments)
      val appInfo = raml.AppInfo.fromParent(
        parent = app,
        deployments = Some(Seq(raml.Identifiable("deployment-123"))),
        tasksStaged = Some(1),
        tasksRunning = Some(2),
        tasksHealthy = Some(3),
        tasksUnhealthy = Some(4)
      )
      appInfoService.selectAppsBy(any, Matchers.eq(expectedEmbeds)) returns Future.successful(Seq(appInfo))

      When("The the index is fetched without any filters")
      val response = asyncRequest { r => appsResource.index(null, null, null, new java.util.HashSet(), auth.request, r) }

      Then("The response holds counts and deployments")
      val appJson = Json.parse(response.getEntity.toString)
      (appJson \ "apps" \\ "deployments" head) should be(Json.arr(Json.obj("id" -> "deployment-123")))
      (appJson \ "apps" \\ "tasksStaged" head) should be(JsNumber(1))
    }

    "Index passes with embed LastTaskFailure (regression for #4765)" in new Fixture {
      Given("An app and group")
      val app = raml.App(id = "/app", cmd = Some("foo"), role = Some("*"))
      val expectedEmbeds: Set[Embed] = Set(Embed.Counts, Embed.Deployments, Embed.LastTaskFailure)
      val appInfo = raml.AppInfo.fromParent(
        parent = app,
        lastTaskFailure =
          Some(raml.TaskFailure("/", "", "", "TASK_STAGING", "", Timestamp.now().toOffsetDateTime, Timestamp.now().toOffsetDateTime)),
        tasksStaged = Some(1),
        tasksRunning = Some(2),
        tasksHealthy = Some(3),
        tasksUnhealthy = Some(4)
      )
      appInfoService.selectAppsBy(any, Matchers.eq(expectedEmbeds)) returns Future.successful(Seq(appInfo))

      When("The the index is fetched with last  task failure")
      val embeds = new java.util.HashSet[String]()
      embeds.add("apps.lastTaskFailure")
      val response = asyncRequest { r => appsResource.index(null, null, null, embeds, auth.request, r) }

      Then("The response holds counts and task failure")
      val appJson = Json.parse(response.getEntity.toString)
      ((appJson \ "apps" \\ "lastTaskFailure" head) \ "state") should be(JsDefined(JsString("TASK_STAGING")))
      (appJson \ "apps" \\ "tasksStaged" head) should be(JsNumber(1))
    }

    "Search apps can be filtered" in new Fixture {
      val app1 =
        AppDefinition(id = AbsolutePathId("/app/service-a"), cmd = Some("party hard"), labels = Map("a" -> "1", "b" -> "2"), role = "*")
      val app2 =
        AppDefinition(id = AbsolutePathId("/app/service-b"), cmd = Some("work hard"), labels = Map("a" -> "1", "b" -> "3"), role = "*")
      val apps = Set(app1, app2)

      def search(cmd: Option[String], id: Option[String], label: Option[String]): Set[AppDefinition] = {
        val selector = appsResource.search(cmd, id, label)
        apps.filter(selector.matches)
      }

      search(cmd = None, id = None, label = None) should be(Set(app1, app2))
      search(cmd = Some(""), id = None, label = None) should be(Set(app1, app2))
      search(cmd = Some("party"), id = None, label = None) should be(Set(app1))
      search(cmd = Some("work"), id = None, label = None) should be(Set(app2))
      search(cmd = Some("hard"), id = None, label = None) should be(Set(app1, app2))
      search(cmd = Some("none"), id = None, label = None) should be(Set.empty)

      search(cmd = None, id = Some("app"), label = None) should be(Set(app1, app2))
      search(cmd = None, id = Some("service-a"), label = None) should be(Set(app1))
      search(cmd = Some("party"), id = Some("app"), label = None) should be(Set(app1))
      search(cmd = Some("work"), id = Some("app"), label = None) should be(Set(app2))
      search(cmd = Some("hard"), id = Some("service-a"), label = None) should be(Set(app1))
      search(cmd = Some(""), id = Some(""), label = None) should be(Set(app1, app2))

      search(cmd = None, id = None, label = Some("b==2")) should be(Set(app1))
      search(cmd = Some("party"), id = Some("app"), label = Some("a==1")) should be(Set(app1))
      search(cmd = Some("work"), id = Some("app"), label = Some("a==1")) should be(Set(app2))
      search(cmd = Some("hard"), id = Some("service-a"), label = Some("a==1")) should be(Set(app1))
      search(cmd = Some(""), id = Some(""), label = Some("")) should be(Set(app1, app2))
    }

    "access without authentication is denied" in new Fixture() {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request
      val embed = new util.HashSet[String]()
      val app = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""
      groupManager.rootGroup() returns Builders.newRootGroup()

      When("we try to fetch the list of apps")
      val index = asyncRequest { r =>
        appsResource.index("", "", "", embed, req, r)
      }
      Then("we receive a NotAuthenticated response")
      index.getStatus should be(auth.NotAuthenticatedStatus)

      When("we try to add an app")
      val create = asyncRequest { r =>
        appsResource.create(app.getBytes("UTF-8"), force = false, req, r)
      }
      Then("we receive a NotAuthenticated response")
      create.getStatus should be(auth.NotAuthenticatedStatus)

      When("we try to fetch an app")
      val show = asyncRequest { r =>
        appsResource.show("", embed, req, r)
      }
      Then("we receive a NotAuthenticated response")
      show.getStatus should be(auth.NotAuthenticatedStatus)

      When("we try to update an app")
      val replace = asyncRequest { r =>
        appsResource.replace("", app.getBytes("UTF-8"), force = false, partialUpdate = true, req, r)
      }

      Then("we receive a NotAuthenticated response")
      replace.getStatus should be(auth.NotAuthenticatedStatus)

      When("we try to update multiple apps")
      val replaceMultiple = asyncRequest { r =>
        appsResource.replaceMultiple(force = false, partialUpdate = true, s"[$app]".getBytes("UTF-8"), req, r)
      }

      Then("we receive a NotAuthenticated response")
      replaceMultiple.getStatus should be(auth.NotAuthenticatedStatus)

      When("we try to delete an app")
      val delete = asyncRequest { r =>
        appsResource.delete(force = false, "", req, r)
      }

      Then("we receive a NotAuthenticated response")
      delete.getStatus should be(auth.NotAuthenticatedStatus)

      When("we try to restart an app")
      val restart = asyncRequest { r =>
        appsResource.restart("", force = false, req, r)
      }

      Then("we receive a NotAuthenticated response")
      restart.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied" in new FixtureWithRealGroupManager(
      initialRoot = Builders.newRootGroup(apps = Seq(Builders.newAppDefinition.command("/a".toAbsolutePath)))
    ) {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      val embed = new util.HashSet[String]()
      val app = """{"id":"/a","cmd":"foo","ports":[]}"""

      When("we try to create an app")
      val create = asyncRequest { r =>
        appsResource.create(app.getBytes("UTF-8"), force = false, req, r)
      }
      Then("we receive a NotAuthorized response")
      create.getStatus should be(auth.UnauthorizedStatus)

      When("we try to fetch an app")
      val show = asyncRequest { r =>
        appsResource.show("*", embed, req, r)
      }
      Then("we receive a NotAuthorized response")
      show.getStatus should be(auth.UnauthorizedStatus)

      When("we try to update an app")
      val replace = asyncRequest { r =>
        appsResource.replace("/a", app.getBytes("UTF-8"), force = false, partialUpdate = true, req, r)
      }

      Then("we receive a NotAuthorized response")
      replace.getStatus should be(auth.UnauthorizedStatus)

      When("we try to update multiple apps")
      val replaceMultiple = asyncRequest { r =>
        appsResource.replaceMultiple(force = false, partialUpdate = true, s"[$app]".getBytes("UTF-8"), req, r)
      }

      Then("we receive a NotAuthorized response")
      replaceMultiple.getStatus should be(auth.UnauthorizedStatus)

      When("we try to remove an app")
      val delete = asyncRequest { r =>
        appsResource.delete(force = false, "/a", req, r)
      }

      Then("we receive a NotAuthorized response")
      delete.getStatus should be(auth.UnauthorizedStatus)

      When("we try to restart an app")
      val restart = asyncRequest { r =>
        appsResource.restart("/a", force = false, req, r)
      }

      Then("we receive a NotAuthorized response")
      restart.getStatus should be(auth.UnauthorizedStatus)
    }

    "access with limited authorization gives a filtered apps listing" in new Fixture {
      Given("An authorized identity with limited ACL's")
      auth.authFn = (resource: Any) => {
        val id = resource match {
          case app: AppDefinition => app.id.toString
          case _ => resource.asInstanceOf[Group].id.toString
        }
        id.startsWith("/visible")
      }
      implicit val identity = auth.identity
      val selector = appsResource.selectAuthorized(Selector.forall(Seq.empty))
      val apps = Seq(
        AppDefinition(AbsolutePathId("/visible/app"), role = "*"),
        AppDefinition(AbsolutePathId("/visible/other/foo/app"), role = "*"),
        AppDefinition(AbsolutePathId("/secure/app"), role = "*"),
        AppDefinition(AbsolutePathId("/root"), role = "*"),
        AppDefinition(AbsolutePathId("/other/great/app"), role = "*")
      )

      When("The selector selects applications")
      val filtered = apps.filter(selector.matches)

      Then("The list of filtered apps only contains apps according to ACL's")
      filtered should have size 2
      filtered.head should be(apps.head)
      filtered(1) should be(apps(1))
    }

    "delete with authorization gives a 404 if the app doesn't exist" in new FixtureWithRealGroupManager() {
      Given("An authenticated identity with full access")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      When("We try to remove a non-existing application")

      Then("A 404 is returned")
      val response = asyncRequest { r =>
        appsResource.delete(force = false, "/foo", req, r)
      }
      response.getStatus shouldBe 404
    }

    "AppUpdate does not change existing versionInfo" in new Fixture {
      implicit val identity = auth.identity
      val app = AppDefinition(
        id = AbsolutePathId("/test"),
        cmd = Some("sleep 1"),
        versionInfo = VersionInfo.forNewConfig(Timestamp(1)),
        role = "*"
      )

      val updateCmd = AppUpdate(cmd = Some("sleep 2"))
      val updatedApp = AppHelpers.updateOrCreate(
        appId = app.id,
        existing = Some(app),
        appUpdate = updateCmd,
        allowCreation = false,
        partialUpdate = true,
        now = clock.now(),
        service = service
      )
      assert(updatedApp.versionInfo == app.versionInfo)
    }

    "Creating an app with artifacts to fetch specified should succeed and return all the artifact properties passed" in new Fixture {
      val app = App(id = "/app", cmd = Some("foo"))
      prepareApp(app, groupManager)

      Given("An app with artifacts to fetch provided")
      val body =
        """
           |{
           |  "id": "/fetch",
           |  "cmd": "sleep 600",
           |  "cpus": 0.1,
           |  "mem": 10,
           |  "instances": 1,
           |  "fetch": [
           |    {
           |      "uri": "file:///bin/bash",
           |      "extract": false,
           |      "executable": true,
           |      "cache": false,
           |      "destPath": "bash.copy"
           |    }
           |  ]
           |}
      """.stripMargin

      When("The request is processed")
      val response = asyncRequest { r =>
        appsResource.create(body.getBytes("UTF-8"), false, auth.request, r)
      }

      Then("The response has no error and it is valid")
      response.getStatus should be(201)
      val appJson = Json.parse(response.getEntity.toString)
      // TODO: are we violating appInfo here?
      (appJson \ "fetch" \ 0 \ "uri" get) should be(JsString("file:///bin/bash"))
      (appJson \ "fetch" \ 0 \ "extract" get) should be(JsBoolean(false))
      (appJson \ "fetch" \ 0 \ "executable" get) should be(JsBoolean(true))
      (appJson \ "fetch" \ 0 \ "cache" get) should be(JsBoolean(false))
      (appJson \ "fetch" \ 0 \ "destPath" get) should be(JsString("bash.copy"))
    }

    "Allow creating app with network name with underscore" in new Fixture {
      Given("An app with a network name with underscore")
      val container = RamlContainer(`type` = EngineType.Mesos, docker = Some(DockerContainer(image = "image")))
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        container = Some(container),
        networks = Seq(Network(name = Some("name_with_underscore"), mode = NetworkMode.Container))
      )
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      assert(response.getStatus == 201, s"body=${new String(body)}, response=${response.getEntity.toString}")
    }

    "Allow editing a env configuration without sending secrets" in new Fixture(configArgs = Seq("--enable_features", "secrets")) {
      Given("An app with a secret")
      val app = App(
        id = "/app",
        cmd = Some("cmd"),
        env = Map("DATABASE_PW" -> EnvVarSecret("database")),
        secrets = Map("database" -> SecretDef("dbpassword"))
      )
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)

      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      Then("It is successful")
      response.getStatus should be(201)
      response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

      When("Env is updated with a PUT request")
      clock.advanceBy(5.seconds)

      val update =
        """
          |{
          |  "env": {
          |    "DATABASE_PW": {
          |      "secret": "database"
          |    },
          |    "foo":"bar"
          |  }
          |}
        """.stripMargin.getBytes("UTF-8")
      val updateResponse = asyncRequest { r =>
        appsResource.replace(app.id, update, force = false, partialUpdate = true, auth.request, r)
      }

      Then("It is successful")
      updateResponse.getStatus should be(200)
      updateResponse.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)

    }

    "Create a new top-level app with no role defined should set role to default role" in new Fixture() {
      Given("An app without a role")
      val app = App(id = "/app", cmd = Some("cmd"))
      val (body, plan) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val result = Try {
        val response = asyncRequest { r =>
          appsResource.create(body, force = false, auth.request, r)
        }

        Then("It is successful")
        response.getStatus should be(201)

        And("the JSON is as expected, including a defined role")
        val expected = raml.AppInfo.fromParent(
          parent = normalize(app).copy(
            role = Some(ResourceRole.Unreserved),
            version = Some(clock.now().toOffsetDateTime),
            upgradeStrategy = Some(raml.UpgradeStrategy(1.0, 1.0))
          ),
          tasks = None,
          tasksStaged = Some(0),
          tasksRunning = Some(0),
          tasksUnhealthy = Some(0),
          tasksHealthy = Some(0),
          deployments = Some(Seq(raml.Identifiable(plan.id)))
        )
        JsonTestHelper.assertThatJsonString(response.getEntity.toString).correspondsToJsonOf(expected)
      }
      if (!result.isSuccess) {
        result.failed.foreach {
          case v: ValidationFailedException =>
            assert(result.isSuccess, s"JSON body = ${new String(body)} :: violations = ${v.failure.violations}")
          case th =>
            throw th
        }
      }
    }

    "Create a new top-level app with a custom role defined should reject " in new Fixture() {
      Given("An app with non-default role")
      val app = App(id = "/app", cmd = Some("cmd"), role = Some("NotTheDefaultRole"))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates a validation error")
        response.getStatus should be(422)
        response.getEntity.toString should include("/role")
        response.getEntity.toString should include("got NotTheDefaultRole, expected ")
      }
    }

    "Create a new top-level app with the mesos_role role defined should accept " in new Fixture(
      configArgs = Seq("--mesos_role", "customMesosRole")
    ) {
      Given("An app with the mesos_role role")
      val app = App(id = "/app", cmd = Some("cmd"), role = Some("customMesosRole"))
      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates success")
        response.getStatus should be(201)
      }
    }

    "Create a new app inside a group with a custom role defined should reject " in new Fixture() {
      Given("An app with non-default role")
      val app = App(id = "/dev/app", cmd = Some("cmd"), role = Some("NotTheDefaultRole"))

      prepareGroup("/dev", groupManager)

      val body = Json.stringify(Json.toJson(normalize(app))).getBytes("UTF-8")

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates a validation error")
        response.getStatus should be(422)
        response.getEntity.toString should include("/role")
        response.getEntity.toString should include("got NotTheDefaultRole, expected ")
      }
    }

    "Update an app inside a group with a custom role defined should accept if custom role is the same" in new Fixture() {
      Given("An app with non-default role")
      val app = App(id = "/dev/app", cmd = Some("cmd"), role = Some("NotTheDefaultRole"))

      prepareGroup("/dev", groupManager)

      val (body, _) = prepareAppInGroup(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates success")
        response.getStatus should be(201)
      }
    }

    "Update an app inside a group with a different custom role should reject" in new Fixture() {
      Given("An app with non-default role")
      val app = App(id = "/dev/app", cmd = Some("cmd"), role = Some("NotTheDefaultRole"))

      prepareGroup("/dev", groupManager)

      prepareAppInGroup(app, groupManager)
      val appWithDifferentCustomRole = app.copy(role = Some("differentCustomRole"))
      val body = Json.stringify(Json.toJson(normalize(appWithDifferentCustomRole))).getBytes("UTF-8")

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates a validation error")
        response.getStatus should be(422)
        response.getEntity.toString should include("/role")
        response.getEntity.toString should include("got differentCustomRole, expected one of: [*, dev, NotTheDefaultRole]")
      }
    }

    "Create a new app inside a group with the mesos_role role defined should accept " in new Fixture(
      configArgs = Seq("--mesos_role", "customMesosRole")
    ) {
      Given("An app with the mesos_role role")
      val app = App(id = "/dev/app", cmd = Some("cmd"), role = Some("customMesosRole"))
      val (body, _) = prepareAppInGroup(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates success")
        response.getStatus should be(201)
      }
    }

    "Create a new app inside a group with the top-group role defined should accept " in new Fixture() {
      Given("An app with the dev role inside a group dev")
      val app = App(id = "/dev/app", cmd = Some("cmd"), role = Some("dev"))
      val (body, _) = prepareAppInGroup(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates success")
        response.getStatus should be(201)
      }
    }

    "Create a new app inside a sub group with the sub-group name as role defined should reject " in new Fixture() {
      Given("An app with the dev role inside a group dev")
      val app = App(id = "/dev/sub/something/app", cmd = Some("cmd"), role = Some("sub"))

      val body = Json.stringify(Json.toJson(normalize(app))).getBytes("UTF-8")

      prepareGroup("/dev/sub/something", groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates success")
        response.getStatus should be(422)
        response.getEntity.toString should include("/role")
        response.getEntity.toString should include("got sub, expected ")
      }
    }

    "Create a new app inside a sub group with the top-group role defined should accept " in new Fixture() {
      Given("An app with the dev role inside a group dev")
      val app = App(id = "/dev/sub/something/app", cmd = Some("cmd"), role = Some("dev"))
      val (body, _) = prepareAppInGroup(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates success")
        response.getStatus should be(201)
      }
    }

    "causes auto-created groups to respect the --new_group_enforce_role=top setting" in new FixtureWithRealGroupManager(
      configArgs = Seq("--new_group_enforce_role", "top")
    ) {
      service.deploy(any, any) returns Future.successful(Done)

      Given("Empty Marathon with --new_group_enforce_role=top setting")

      When("I post a sleeper app definition to /dev/apps/sleeper")
      val response = asyncRequest { r =>
        val body =
          """
            |{
            |  "id": "/dev/apps/sleeper",
            |  "cmd": "sleep 3600",
            |  "instances": 1,
            |  "cpus": 0.05,
            |  "mem": 128
            |}
          """.stripMargin
        appsResource.create(body.getBytes, force = false, auth.request, r)
      }

      response.getStatus should be(201)

      Then("the app has the role 'dev' automatically set")
      val Some(app) = groupManager.rootGroup().app(AbsolutePathId("/dev/apps/sleeper"))

      app.role shouldBe "dev"
      And("the auto-created top-level group has enforceRole enabled")
      val Some(group) = groupManager.group("/dev".toAbsolutePath)
      group.enforceRole shouldBe Some(true)

      And("the auto-created mid-level group has enforceRole undefined")
      val Some(midGroup) = groupManager.group("/dev/apps".toAbsolutePath)
      midGroup.enforceRole shouldBe None
    }

    "Create a new app inside a top-level group with enforceRole disabled but --new_group_enforce_role=top" in new FixtureWithRealGroupManager(
      configArgs = Seq("--new_group_enforce_role", "top"),
      initialRoot = Builders.newGroup
        .withoutParentAutocreation(id = "/".toAbsolutePath, groups = Seq(Group.empty("/dev".toAbsolutePath, enforceRole = Some(false))))
    ) {

      service.deploy(any, any) returns Future.successful(Done)

      Given("group /dev with enforceRole: false")
      And("Marathon is configured with --new_group_enforce_role=top")

      When("I post a sleeper app definition to /dev/sleeper without specifying the role")
      val response = asyncRequest { r =>
        val body =
          """
            |{
            |  "id": "/dev/sleeper",
            |  "cmd": "sleep 3600",
            |  "instances": 1,
            |  "cpus": 0.05,
            |  "mem": 128
            |}
          """.stripMargin
        appsResource.create(body.getBytes, force = false, auth.request, r)
      }

      Then("the request should succeed")
      response.getStatus should be(201)

      val Some(app) = groupManager.rootGroup().app(AbsolutePathId("/dev/sleeper"))

      And("the role should default to the default mesos_role '*'")
      app.role shouldBe "*"
    }

    "Create a new app inside a sub-group for which enforceRole is enabled applies the proper group-role default" in new FixtureWithRealGroupManager(
      initialRoot = Builders.newGroup
        .withoutParentAutocreation("/".toAbsolutePath, groups = Seq(Group.empty("/dev".toAbsolutePath, enforceRole = Some(true))))
    ) {
      service.deploy(any, any) returns Future.successful(Done)

      Given("group /dev with enforceRole: true")

      When("I post a sleeper app definition to subgroup /dev/backend")
      val response = asyncRequest { r =>
        val body =
          """
            |{
            |  "id": "/dev/backend/sleeper",
            |  "cmd": "sleep 3600",
            |  "instances": 1,
            |  "cpus": 0.05,
            |  "mem": 128
            |}
          """.stripMargin
        appsResource.create(body.getBytes, force = false, auth.request, r)
      }

      response.getStatus should be(201)

      val Some(app) = groupManager.rootGroup().app(AbsolutePathId("/dev/backend/sleeper"))

      app.role shouldBe "dev"
    }

    "Create a new app inside a top-level group with enforceRole applies the proper group-role default" in new FixtureWithRealGroupManager(
      initialRoot = Builders.newGroup
        .withoutParentAutocreation("/".toAbsolutePath, groups = Seq(Group.empty("/dev".toAbsolutePath, enforceRole = Some(true))))
    ) {
      service.deploy(any, any) returns Future.successful(Done)

      Given("group /dev with enforceRole: true")

      When("I post a sleeper app definition without a leading slash to dev/sleeper")
      val response = asyncRequest { r =>
        val body =
          """
            |{
            |  "id": "dev/sleeper",
            |  "cmd": "sleep 3600",
            |  "instances": 1,
            |  "cpus": 0.05,
            |  "mem": 128
            |}
          """.stripMargin
        appsResource.create(body.getBytes, force = false, auth.request, r)
      }

      response.getStatus should be(201)

      val Some(app) = groupManager.rootGroup().app(AbsolutePathId("/dev/sleeper"))

      app.role shouldBe "dev"
    }

    "Create an app in root with acceptedResourceRoles = default Mesos role" in new Fixture(
      configArgs = Seq("--mesos_role", "customMesosRole")
    ) {
      Given("An app with the mesos_role role")
      val app = App(id = "/app-with-accepted-default-mesos-role", cmd = Some("cmd"), acceptedResourceRoles = Some(Set("customMesosRole")))

      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates success")
        response.getStatus should be(201)
      }
    }

    "Create an app in root with acceptedResourceRoles = customMesosRole with an invalid acceptedResourceRoles value" in new Fixture(
      configArgs = Seq("--deprecated_features", "disable_sanitize_accepted_resource_roles")
    ) {
      Given("An app with an unknown acceptedResourceRole")
      val app =
        App(id = "/app-with-not-accepted-unknown-mesos-role", cmd = Some("cmd"), acceptedResourceRoles = Some(Set("customMesosRole")))

      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates a failure")
        response.getStatus should be(422)
        response.getEntity.asInstanceOf[String] contains "acceptedResourceRoles can only contain *"
      }

    }

    "Create a new resident app with role * should reject " in new Fixture() {
      Given("An app with non-default role")
      val app = App(
        id = "/dev/app",
        role = Some("*"),
        cmd = Some("cmd"),
        container = Some(
          raml.Container(
            docker = Some(DockerContainer(image = "someimage")),
            volumes = Seq(
              AppPersistentVolume(
                containerPath = "helloworld",
                mode = ReadMode.Rw,
                persistent = raml.PersistentVolumeInfo(size = 10)
              )
            )
          )
        )
      )

      prepareGroup("/dev", groupManager)

      val body = Json.stringify(Json.toJson(normalize(app))).getBytes("UTF-8")

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        println("Response Status: " + response.getStatus + " >> " + response.getEntity.toString)
        Then("The return code indicates a validation error")
        response.getStatus should be(422)
        response.getEntity.toString should include("/role")
        response.getEntity.toString should include("Resident apps cannot have the role *")
      }
    }

    "ExternalVolume names with options are considered valid" in new Fixture() {
      Given("an app with external volume whose name serializes options")
      val volumeName = "name=teamvolumename,repl=1,secure=true,secret_key=volume-secret-key-team"
      val appWithExtVol: String =
        s"""
          |{
          |  "id": "nginx-ucr",
          |  "container": {
          |    "type": "MESOS",
          |    "volumes": [
          |      {
          |        "external": {
          |          "size": 5,
          |          "name": "$volumeName",
          |          "provider": "dvdi",
          |          "options": {
          |            "dvdi/driver": "pxd",
          |            "dvdi/secure": "true",
          |            "dvdi/secret_key": "volume-secret-key-team",
          |            "dvdi/repl": "1",
          |            "dvdi/shared": "true"
          |          }
          |        },
          |        "mode": "RW",
          |        "containerPath": "/mnt/nginx"
          |      }
          |    ],
          |    "docker": {
          |      "image": "nginx",
          |      "forcePullImage": false,
          |      "parameters": []
          |    },
          |    "portMappings": [
          |      {
          |        "containerPort": 80,
          |        "hostPort": 0,
          |        "protocol": "tcp",
          |        "name": "web"
          |      }
          |    ]
          |  },
          |  "cpus": 0.1,
          |  "disk": 0,
          |  "instances": 1,
          |  "mem": 128,
          |  "gpus": 0,
          |  "networks": [
          |    {
          |      "mode": "container/bridge"
          |    }
          |  ],
          |  "requirePorts": false,
          |  "healthChecks": [],
          |  "fetch": [],
          |  "constraints": []
          |}
        """.stripMargin
      val appJs = Json.parse(appWithExtVol)
      val app = appJs.as[raml.App]

      val (body, _) = prepareApp(app, groupManager)

      When("The create request is made")
      clock.advanceBy(5.seconds)
      val response = asyncRequest { r =>
        appsResource.create(body, force = false, auth.request, r)
      }

      withClue(response.getEntity.toString) {
        Then("The return code indicates success")
        response.getStatus should be(201)
      }

      And("the resulting app has the given volume name")
      val appJson = Json.parse(response.getEntity.toString)
      (appJson \ "container" \ "volumes" \ 0 \ "external" \ "name") should be(JsDefined(JsString(volumeName)))
    }
  }
}
