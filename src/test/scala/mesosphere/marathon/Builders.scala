package mesosphere.marathon

import java.util.concurrent.atomic.AtomicInteger

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.core.check.Check
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.core.pod.{Network, PodDefinition}
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.raml.{App, Apps, Resources}
import mesosphere.marathon.state.RootGroup.NewGroupStrategy
import mesosphere.marathon.state.{
  AbsolutePathId,
  AppDefinition,
  BackoffStrategy,
  EnvVarValue,
  Group,
  KillSelection,
  PortDefinition,
  ResourceLimits,
  Role,
  RootGroup,
  Secret,
  Timestamp,
  UnreachableStrategy,
  UpgradeStrategy,
  VersionInfo
}

import scala.concurrent.duration.FiniteDuration

/**
  * Home for constructors of common test fixtures.
  *
  * Convention: new{EntityType}.{variant}, where variant describes some recipe for creating different variations of standard fixtures; for example, a command app definition.
  *
  * For the standard variant, `apply` is used.
  *
  * All builders return valid objects with the least amount of information required (IE - where empty list or None is valid, this is used as a default)
  */
object Builders {

  object newRootGroup {

    /**
      * Construct a new rootGroup containing the specified pods and apps. Interim groups are automatically created.
      * @param apps Apps to include, in any nested path structure.
      * @param pods Pods to include, in any nested path structure.
      * @param newGroupEnforceRoleBehavior Controls the default value for enforceRole in interim created groups.
      * @return Root group containing the apps and pods specified
      */
    def apply(
        apps: Iterable[AppDefinition] = Nil,
        pods: Iterable[PodDefinition] = Nil,
        groupDependencies: Map[AbsolutePathId, Set[AbsolutePathId]] = Map.empty,
        groupIds: Iterable[AbsolutePathId] = Nil,
        newGroupEnforceRoleBehavior: NewGroupEnforceRoleBehavior = NewGroupEnforceRoleBehavior.Top,
        version: Timestamp = Group.defaultVersion
    ): RootGroup = {
      val newGroupStrategy = NewGroupStrategy.fromConfig(newGroupEnforceRoleBehavior)
      val initialGroup = RootGroup(newGroupStrategy = newGroupStrategy, version = version)
      val withEmptyGroups = groupIds.foldLeft(initialGroup) { (rootGroup, groupId) =>
        rootGroup.updateGroup(groupId, _.getOrElse { newGroupStrategy.newGroup(groupId) })
      }
      val groupWithApps = apps.foldLeft(withEmptyGroups) { (rootGroup, app) =>
        rootGroup.updateApp(app.id, _ => app, version = version)
      }
      val groupWithPods = pods.foldLeft(groupWithApps) { (rootGroup, pod) =>
        rootGroup.updatePod(pod.id, _ => pod, version = version)
      }
      groupDependencies.foldLeft(groupWithPods) {
        case (rootGroup, (groupId, dependencies)) =>
          rootGroup.updateGroup(
            groupId,
            {
              case Some(group) => group.withDependencies(dependencies)
              case None => newGroupStrategy.newGroup(groupId).withDependencies(dependencies)
            }
          )
      }
    }
  }

  object newAppDefinition {
    val appIdIncrementor = new AtomicInteger()

    /** Return a valid command app definition (using command executor, not using UCR or Docker). */
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
        role: Role = "*",
        resourceLimits: Option[ResourceLimits] = None
    ): AppDefinition = {
      AppDefinition(
        id = id,
        role = role,
        cmd = cmd,
        user = user,
        env = env,
        args = args,
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
        tty = tty,
        resourceLimits = resourceLimits
      )
    }
  }
}
