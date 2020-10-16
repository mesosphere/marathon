package mesosphere.marathon
package state

import java.util.Objects

import com.typesafe.scalalogging.StrictLogging
import com.wix.accord.Descriptions.{Explicit, Generic, Path}
import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.PathId.{StringPathId, validPathWithBase}
import mesosphere.util.summarize

class Group protected (
    val id: AbsolutePathId,
    val apps: Map[AbsolutePathId, AppDefinition],
    val pods: Map[AbsolutePathId, PodDefinition],
    val groupsById: Map[AbsolutePathId, Group],
    val dependencies: Set[AbsolutePathId],
    val version: Timestamp,
    val enforceRole: Option[Boolean]
) extends mesosphere.marathon.plugin.Group {

  if (id.isTopLevel) {
    require(enforceRole.nonEmpty, "Top-level groups must specify role enforcement.")
  } else {
    require(enforceRole.isEmpty, "Only top-level groups can specify role enforcement.")
  }

  /**
    * Get app from this group or any child group.
    *
    * @param appId The app to retrieve.
    * @return None if the app was not found or non empty option with app.
    */
  def app(appId: AbsolutePathId): Option[AppDefinition] = {
    apps.get(appId) orElse group(appId.parent).flatMap(_.apps.get(appId))
  }

  /**
    * Get pod from this group or any child group.
    *
    * @param podId The pod to retrieve.
    * @return None if the pod was not found or non empty option with pod.
    */
  def pod(podId: AbsolutePathId): Option[PodDefinition] = {
    pods.get(podId) orElse group(podId.parent).flatMap(_.pods.get(podId))
  }

  /**
    * Get a runnable specification from this group or any child group.
    *
    * @param id The path of the run spec to retrieve.
    * @return None of run spec was not found or non empty option with run spec.
    */
  def runSpec(id: AbsolutePathId): Option[RunSpec] = {
    val maybeApp = this.app(id)
    if (maybeApp.isDefined) maybeApp else this.pod(id)
  }

  /**
    * Checks whether a runnable spec with the given id exists.
    *
    * @param id Id of an app or pod.
    * @return True if app or pod exists, false otherwise.
    */
  def exists(id: AbsolutePathId): Boolean = runSpec(id).isDefined

  /**
    * Find and return the child group for the given path.
    *
    * @param gid The path of the group for find.
    * @return None if no group was found or non empty option with group.
    */
  def group(gid: AbsolutePathId): Option[Group] = transitiveGroupsById.get(gid)

  def transitiveAppsIterator(): Iterator[AppDefinition] =
    apps.valuesIterator ++ groupsById.valuesIterator.flatMap(_.transitiveAppsIterator())
  private def transitiveAppIdsIterator(): Iterator[AbsolutePathId] =
    apps.keysIterator ++ groupsById.valuesIterator.flatMap(_.transitiveAppIdsIterator())
  lazy val transitiveApps: Iterable[AppDefinition] = transitiveAppsIterator().toVector
  lazy val transitiveAppIds: Iterable[AbsolutePathId] = transitiveAppIdsIterator().toVector

  def transitivePodsIterator(): Iterator[PodDefinition] =
    pods.valuesIterator ++ groupsById.valuesIterator.flatMap(_.transitivePodsIterator())
  private def transitivePodIdsIterator(): Iterator[AbsolutePathId] =
    pods.keysIterator ++ groupsById.valuesIterator.flatMap(_.transitivePodIdsIterator())
  lazy val transitivePods: Iterable[PodDefinition] = transitivePodsIterator().toVector
  lazy val transitivePodIds: Iterable[AbsolutePathId] = transitivePodIdsIterator().toVector

  lazy val transitiveRunSpecs: Iterable[RunSpec] = transitiveApps ++ transitivePods
  lazy val transitiveRunSpecIds: Iterable[AbsolutePathId] = transitiveAppIds ++ transitivePodIds

  def transitiveGroups(): Iterator[(AbsolutePathId, Group)] = groupsById.iterator ++ groupsById.valuesIterator.flatMap(_.transitiveGroups())
  lazy val transitiveGroupsById: Map[AbsolutePathId, Group] = {
    val builder = Map.newBuilder[AbsolutePathId, Group]
    builder += id -> this
    builder ++= transitiveGroups()
    builder.result()
  }
  lazy val transitiveGroupValues: Iterable[Group] = transitiveGroups().map(_._2).toVector

  /** @return true if and only if this group directly or indirectly contains app definitions. */
  def containsApps: Boolean = apps.nonEmpty || groupsById.exists { case (_, group) => group.containsApps }

  def containsPods: Boolean = pods.nonEmpty || groupsById.exists { case (_, group) => group.containsPods }

  def containsAppsOrPodsOrGroups: Boolean = apps.nonEmpty || groupsById.nonEmpty || pods.nonEmpty

  override def equals(other: Any): Boolean =
    other match {
      case that: Group =>
        id == that.id &&
          apps == that.apps &&
          pods == that.pods &&
          groupsById == that.groupsById &&
          dependencies == that.dependencies &&
          version == that.version
      case _ => false
    }

  override def hashCode(): Int = Objects.hash(id, apps, pods, groupsById, dependencies, version)

  override def toString = {
    val summarizedApps = summarize(apps.valuesIterator.map(_.id))
    val summarizedPods = summarize(pods.valuesIterator.map(_.id))
    val summarizedGroups = summarize(groupsById.valuesIterator.map(_.id))
    val summarizedDependencies = summarize(dependencies.iterator)

    s"Group($id, apps = $summarizedApps, pods = $summarizedPods, groups = $summarizedGroups, dependencies = $summarizedDependencies, version = $version, enforceRole = $enforceRole)"
  }

  /** @return a copy of this group with an updated `enforceRole` field. */
  def withEnforceRole(enforceRole: Boolean): Group =
    new Group(this.id, this.apps, this.pods, this.groupsById, this.dependencies, this.version, Some(enforceRole))

  /** @return a copy of this group with the removed `enforceRole` field. */
  def withoutEnforceRole(): Group =
    new Group(this.id, this.apps, this.pods, this.groupsById, this.dependencies, this.version, enforceRole = Some(false))

  def withDependencies(dependencies: Set[AbsolutePathId]): Group =
    new Group(this.id, this.apps, this.pods, this.groupsById, dependencies, this.version, this.enforceRole)

  /**
    * Builds a pretty tree of the group
    *
    * {{{
    * /
    * ├── apps(0)
    * ├── pods(0)
    * ├── def
    * │    ├── apps(3)
    * │    └── pods(0)
    * └── prod
    *     ├── apps(3)
    *     └── pods(0)
    * }}}
    *
    * @return the tree as a string.
    */
  def prettyTree(): String = {
    val builder = new StringBuilder()
    prettyTree(builder, "").toString()
  }

  private def prettyTree(builder: StringBuilder, indent: String): StringBuilder = {

    builder.append(id.path.lastOption.getOrElse("/"))

    // append apps and pods info
    builder.append(s"\n$indent├── apps(${apps.size})")

    if (groupsById.nonEmpty) {
      builder.append(s"\n$indent├── pods(${pods.size})")
    } else {
      builder.append(s"\n$indent└── pods(${pods.size})")
    }

    // append groups
    val iter = groupsById.valuesIterator
    while (iter.hasNext) {
      val childGroup = iter.next()
      val lastElemet = !iter.hasNext
      builder.append("\n").append(indent)

      if (lastElemet) builder.append("└── ") else builder.append("├── ")

      val newIndent = if (lastElemet) indent + "    " else indent + "│    "
      childGroup.prettyTree(builder, indent = newIndent)
    }

    builder
  }
}

object Group extends StrictLogging {

  def apply(
      id: AbsolutePathId,
      apps: Map[AbsolutePathId, AppDefinition],
      pods: Map[AbsolutePathId, PodDefinition],
      groupsById: Map[AbsolutePathId, Group],
      dependencies: Set[AbsolutePathId],
      version: Timestamp,
      enforceRole: Option[Boolean]
  ): Group = {
    val enforceRoleBehaviour = if (id.isTopLevel) enforceRole.orElse(Some(false)) else None
    new Group(id, apps, pods, groupsById, dependencies, version, enforceRoleBehaviour)
  }

  def empty(id: AbsolutePathId, enforceRole: Option[Boolean] = None, version: Timestamp = Timestamp(0)): Group =
    Group(id, Map.empty, Map.empty, Map.empty, Set.empty, version = version, enforceRole = enforceRole)

  val defaultVersion = Timestamp.now()

  def validGroup(base: AbsolutePathId, config: MarathonConf): Validator[Group] =
    validator[Group] { group =>
      group.id is validPathWithBase(base)

      group.transitiveApps as "apps" is everyApp(
        AppDefinition.validBasicAppDefinition(config.availableFeatures) and isChildOfParentId(group)
      )

      group is noAppsAndPodsWithSameId
      group is noAppsAndGroupsWithSameName
      group is noPodsAndGroupsWithSameName

      group.transitiveGroupValues as "groups" is every(
        noAppsAndPodsWithSameId and noAppsAndGroupsWithSameName and noPodsAndGroupsWithSameName
          and isTrue("Group has to be child of groups with parent id") { childGroup =>
            if (childGroup.id.parent == group.id) group.groupsById.contains(childGroup.id)
            else {
              group.group(childGroup.id.parent).exists(parent => parent.groupsById.contains(childGroup.id))
            }
          }
      )
    }

  import scala.language.implicitConversions

  implicit def everyApp(validator: Validator[AppDefinition]): Validator[Iterable[AppDefinition]] = {
    new Validator[Iterable[AppDefinition]] {
      override def apply(seq: Iterable[AppDefinition]): Result = {
        seq.foldLeft[Result](Success) {
          case (accum, (app)) =>
            validator(app) match {
              case Success => accum
              case Failure(violations) =>
                val scopedViolations = violations.map { violation =>
                  violation.withPath(Descriptions.Path(Explicit(app.id.toString)))
                }
                accum.and(Failure(scopedViolations))
            }
        }
      }
    }
  }

  implicit def everyPod(validator: Validator[PodDefinition]): Validator[Iterable[PodDefinition]] = {
    new Validator[Iterable[PodDefinition]] {
      override def apply(seq: Iterable[PodDefinition]): Result = {
        seq.foldLeft[Result](Success) {
          case (accum, (pod)) =>
            validator(pod) match {
              case Success => accum
              case Failure(violations) =>
                val scopedViolations = violations.map { violation =>
                  violation.withPath(Descriptions.Path(Explicit(pod.id.toString)))
                }
                accum.and(Failure(scopedViolations))
            }
        }
      }
    }
  }

  /**
    * An app validator to verify that the app is in a proper child group of group.
    *
    * @param group The parent group for all sub groups to check.
    * @return The validator.
    */
  private def isChildOfParentId(group: Group): Validator[AppDefinition] = {
    isTrue("App has to be child of group with parent id") { app =>
      if (app.id.parent == group.id) group.apps.contains(app.id)
      else {
        group.group(app.id.parent).exists(child => child.apps.contains(app.id))
      }
    }
  }

  private def noAppsAndPodsWithSameId: Validator[Group] =
    isTrue("Applications and Pods may not share the same id") { group =>
      !group.transitiveAppIds.exists(appId => group.pod(appId).isDefined)
    }

  private def noAppsAndGroupsWithSameName: Validator[Group] =
    isTrue("Groups and Applications may not have the same identifier.") { group =>
      val childGroupIds = group.groupsById.keys
      val clashingIds = childGroupIds.filter(group.apps.contains(_))
      if (clashingIds.nonEmpty)
        logger.info(s"Found the following clashingIds in group ${group.id}: ${clashingIds}")
      clashingIds.isEmpty
    }

  private def noPodsAndGroupsWithSameName: Validator[Group] =
    isTrue("Groups and Pods may not have the same identifier.") { group =>
      val childGroupIds = group.groupsById.keys
      val clashingIds = childGroupIds.filter(group.pods.contains(_))
      if (clashingIds.nonEmpty)
        logger.info(s"Found the following clashingIds in group ${group.id}: ${clashingIds}")
      clashingIds.isEmpty
    }

  def emptyUpdate(id: PathId): raml.GroupUpdate = raml.GroupUpdate(Some(id.toString))

  /** requires that apps are in canonical form */
  def validNestedGroupUpdateWithBase(
      base: AbsolutePathId,
      originalRootGroup: RootGroup,
      servicesGloballyModified: Boolean
  ): Validator[raml.GroupUpdate] =
    validator[raml.GroupUpdate] { group =>
      group is notNull
      group is definingEnforcingRoleOnlyIfItsTopLevel(base)

      group.version is theOnlyDefinedOptionIn(group)
      group.scaleBy is theOnlyDefinedOptionIn(group)

      // this is funny: id is "optional" only because version and scaleBy can't be used in conjunction with other
      // fields. it feels like we should make an exception for "id" and always require it for non-root groups.
      group.id.map(_.toPath) as "id" is optional(valid)

      group.apps is optional(every(AppValidation.validNestedApp(group.id.fold(base)(PathId(_).canonicalPath(base)))))
      group.groups is optional(
        every(
          validNestedGroupUpdateWithBase(group.id.fold(base)(PathId(_).canonicalPath(base)), originalRootGroup, servicesGloballyModified)
        )
      )
    }.and(disallowEnforceRoleChangeIfServicesChanged(originalRootGroup, base, servicesGloballyModified))

  private case class definingEnforcingRoleOnlyIfItsTopLevel(base: AbsolutePathId) extends Validator[raml.GroupUpdate] {
    override def apply(group: raml.GroupUpdate): Result = {
      val groupId = group.id.fold(base) { id => PathId(id).canonicalPath(base) }
      // Only top-level groups are allowed to set the enforce role parameter.
      if (!groupId.isTopLevel && group.enforceRole.contains(true)) {
        Failure(
          Set(
            RuleViolation(
              group.enforceRole,
              s"enforceRole can only be set for top-level groups, and ${groupId} is not top-level",
              Path(Generic("enforceRole"))
            )
          )
        )
      } else {
        Success
      }
    }
  }

  case class disallowEnforceRoleChangeIfServicesChanged(
      originalRootGroup: RootGroup,
      base: AbsolutePathId,
      servicesGloballyModified: Boolean
  ) extends Validator[raml.GroupUpdate] {
    import disallowEnforceRoleChangeIfServicesChanged.EnforceRoleCantBeChangedMessage

    override def apply(group: raml.GroupUpdate): Result = {
      if (!servicesGloballyModified) {
        Success
      } else {
        val updatedGroupId = group.id.map { id => id.toPath.canonicalPath(base) }
        val originalGroup = updatedGroupId.flatMap { id =>
          originalRootGroup.group(id)
        } // TODO: why is groupUpdate.id optional? What is the semantic there?
        if (originalGroup.isDefined && (group.enforceRole != originalGroup.flatMap(_.enforceRole))) {
          Failure(Set(RuleViolation(group.enforceRole, EnforceRoleCantBeChangedMessage, path = Path(Generic("enforceRole")))))
        } else {
          Success
        }
      }
    }
  }

  object disallowEnforceRoleChangeIfServicesChanged {
    val EnforceRoleCantBeChangedMessage =
      "enforceRole cannot be modified in a request that adds, removes or modifies services; please make these changes in separate requests."
  }

  def updateModifiesServices(update: raml.GroupUpdate): Boolean = {
    update.version.nonEmpty || update.scaleBy.nonEmpty || update.apps.nonEmpty || update.groups.getOrElse(Set.empty).exists { group =>
      updateModifiesServices(group)
    }
  }
}
