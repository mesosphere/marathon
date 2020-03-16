package mesosphere

import java.util.concurrent.atomic.AtomicInteger

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.{NewGroupEnforceRoleBehavior, Seq}
import mesosphere.marathon.core.check.Check
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.core.pod.Network
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.raml.{App, Apps, Resources}
import mesosphere.marathon.state.RootGroup.NewGroupStrategy
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, BackoffStrategy, Container, EnvVarValue, FetchUri, KillSelection, PortDefinition, PortDefinitions, Role, RootGroup, Secret, Timestamp, UnreachableStrategy, UpgradeStrategy, VersionInfo}

import scala.concurrent.duration.FiniteDuration

object Builders {

  object newRootGroup {
    def withApps(apps: Seq[AppDefinition]): RootGroup = {
      apps.foldLeft(RootGroup.empty(NewGroupStrategy.fromConfig(NewGroupEnforceRoleBehavior.Top))) { (rootGroup, app) =>
        rootGroup.updateApp(app.id, _ => app)
      }
    }
  }
  object newAppDefinition {
    val appIdIncrementor = new AtomicInteger()

    def command(
      id: AbsolutePathId = AbsolutePathId(s"/app-${appIdIncrementor.incrementAndGet()}"),
      cmd: Option[String] = Some("sleep 3600"),
      args: Seq[String] = App.DefaultArgs,
      user: Option[String] = App.DefaultUser,
      env: Map[String, EnvVarValue] = AppDefinition.DefaultEnv,
      instances: Int = 1,
      resources: Resources = Apps.DefaultResources,
      constraints: Set[Constraint] = AppDefinition.DefaultConstraints,
      portDefinitions: Seq[PortDefinition] = AppDefinition.DefaultPortDefinitions,
      requirePorts: Boolean = App.DefaultRequirePorts,
      backoffStrategy: BackoffStrategy = AppDefinition.DefaultBackoffStrategy,
      healthChecks: Set[HealthCheck] = AppDefinition.DefaultHealthChecks,
      check: Option[Check] = AppDefinition.DefaultCheck,
      readinessChecks: Seq[ReadinessCheck] = AppDefinition.DefaultReadinessChecks,
      taskKillGracePeriod: Option[FiniteDuration] = AppDefinition.DefaultTaskKillGracePeriod,
      upgradeStrategy: UpgradeStrategy = AppDefinition.DefaultUpgradeStrategy,
      labels: Map[String, String] = AppDefinition.DefaultLabels,
      acceptedResourceRoles: Set[String] = Set("*"),
      networks: Seq[Network] = AppDefinition.DefaultNetworks,
      versionInfo: VersionInfo = VersionInfo.OnlyVersion(Timestamp.now()),
      secrets: Map[String, Secret] = AppDefinition.DefaultSecrets,
      unreachableStrategy: UnreachableStrategy = AppDefinition.DefaultUnreachableStrategy,
      killSelection: KillSelection = KillSelection.DefaultKillSelection,
      tty: Option[Boolean] = AppDefinition.DefaultTTY,
      role: Role = "*"): AppDefinition = {
      AppDefinition(
        id = id,
        role = role,
        cmd = cmd,
        user = user,
        env = env,
        container = None,
        resources = resources,
        instances = instances,
        portDefinitions = portDefinitions,
        executor = "//cmd",
        acceptedResourceRoles = acceptedResourceRoles,
        constraints = constraints,
        requirePorts = requirePorts,
        backoffStrategy = backoffStrategy,
        healthChecks = healthChecks,
        check = check,
        readinessChecks = readinessChecks,
        taskKillGracePeriod = taskKillGracePeriod,
        upgradeStrategy = upgradeStrategy,
        labels = labels,
        networks = networks,
        versionInfo = versionInfo,
        secrets = secrets,
        unreachableStrategy = unreachableStrategy,
        killSelection = killSelection,
        tty = tty)
    }
  }

}
