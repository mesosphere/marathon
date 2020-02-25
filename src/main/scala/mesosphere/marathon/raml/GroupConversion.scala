package mesosphere.marathon
package raml

import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, PathId, Timestamp, Group => CoreGroup, VersionInfo => CoreVersionInfo}

object GroupConversion {
  def apply(groupUpdate: GroupUpdate, current: CoreGroup, timestamp: Timestamp): UpdateGroupStructureOp =
    UpdateGroupStructureOp(groupUpdate, current, timestamp)
}

case class UpdateGroupStructureOp(
    groupUpdate: GroupUpdate,
    current: CoreGroup,
    timestamp: Timestamp
) {
  def apply(cf: App => AppDefinition): CoreGroup = UpdateGroupStructureOp.execute(groupUpdate, current, timestamp)(cf)
}

object UpdateGroupStructureOp {

  import Normalization._

  private def requireGroupPath(groupUpdate: GroupUpdate): PathId = {
    groupUpdate.id.map(PathId(_)).getOrElse(
      // validation should catch this..
      throw SerializationFailedException("No group id was given!")
    )
  }

  private def normalizeAppDefinition(version: Timestamp): Normalization[AppDefinition] = Normalization { app =>
    app.copy(versionInfo = CoreVersionInfo.OnlyVersion(version))
  }

  private def normalizeApp(pathNormalization: Normalization[String]): Normalization[App] = Normalization { app =>
    app.copy(
      id = app.id.normalize(pathNormalization),
      dependencies = app.dependencies.map(_.normalize(pathNormalization)))
  }

  /**
    * Creates a new [[state.Group]] from a [[GroupUpdate]], performing both normalization and conversion.
    */
  private def createGroup(groupUpdate: GroupUpdate, gid: AbsolutePathId, version: Timestamp)(implicit cf: App => AppDefinition): CoreGroup = {
    val pathNormalization: Normalization[String] = Normalization(PathId(_).canonicalPath(gid).toString)
    implicit val appNormalization: Normalization[App] = normalizeApp(pathNormalization)
    implicit val appDefinitionNormalization: Normalization[AppDefinition] = normalizeAppDefinition(version)

    val appsById: Map[AbsolutePathId, AppDefinition] = groupUpdate.apps.getOrElse(Set.empty).iterator.map { currentApp =>
      val app = cf(currentApp.normalize).normalize
      app.id -> app
    }.toMap

    val groupsById: Map[AbsolutePathId, CoreGroup] = groupUpdate.groups.getOrElse(Seq.empty).iterator.map { currentGroup =>
      // TODO: tailrec needed
      val id = requireGroupPath(currentGroup).canonicalPath(gid)
      val group = createGroup(currentGroup, id, version)
      group.id -> group
    }.toMap

    CoreGroup(
      id = gid,
      apps = appsById,
      pods = Map.empty,
      groupsById = groupsById,
      dependencies = groupUpdate.dependencies.fold(Set.empty[AbsolutePathId])(_.map(PathId(_).canonicalPath(gid))),
      version = version
    )
  }

  /**
    * Main entrypoint for a group structure update operation.
    * Implements create-or-update-or-delete for a group tree or subtree.
    * Performs both normalization and conversion from RAML model to state model.
    */
  private def execute(
    groupUpdate: GroupUpdate,
    current: CoreGroup,
    timestamp: Timestamp)(implicit cf: App => AppDefinition): CoreGroup = {

    require(groupUpdate.scaleBy.isEmpty, "For a structural update, no scale should be given.")
    require(groupUpdate.version.isEmpty, "For a structural update, no version should be given.")
    assert(groupUpdate.enforceRole.isDefined, s"BUG! The group normalization should have set enforceRole for ${groupUpdate.id}.")

    implicit val pathNormalization: Normalization[PathId] = Normalization(_.canonicalPath(current.id))
    implicit val appNormalization: Normalization[AppDefinition] = normalizeAppDefinition(timestamp)

    val effectiveGroups: Map[AbsolutePathId, CoreGroup] = groupUpdate.groups.fold(current.groupsById) { updates =>
      updates.iterator.map { groupUpdate =>
        val gid = requireGroupPath(groupUpdate).canonicalPath(current.id)
        val newGroup = current.groupsById.get(gid).map { group =>
          execute(groupUpdate, group, timestamp) // TODO: tailrec needed
        }.getOrElse(createGroup(groupUpdate, gid, timestamp))

        newGroup.id -> newGroup
      }.toMap
    }

    val effectiveApps: Map[AbsolutePathId, AppDefinition] = {
      groupUpdate.apps.map(_.map(cf)).getOrElse(current.apps.values).iterator.map { currentApp =>
        val app = currentApp.normalize
        app.id -> app
      }.toMap
    }

    val effectiveDependencies = groupUpdate.dependencies.fold(current.dependencies)(_.map(PathId(_).canonicalPath(current.id)))

    CoreGroup(
      id = current.id,
      apps = effectiveApps,
      pods = current.pods,
      groupsById = effectiveGroups,
      dependencies = effectiveDependencies,
      version = timestamp,
      enforceRole = groupUpdate.enforceRole.get)
  }
}

