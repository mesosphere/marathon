package mesosphere.marathon
package api

import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.{ GroupConversion, Raml }
import mesosphere.marathon.state._

import scala.async.Async._
import scala.concurrent.{ ExecutionContext, Future }

class GroupApiService(groupManager: GroupManager) {

  /**
    * Encapsulates the group update logic that is following:
    * - if version is set, group is reverted to that version
    * - if scaleBy is set, group is scaled up
    * - if none version nor scaleBy is set then the group is updated
    */
  @SuppressWarnings(Array("all")) // async/await
  def getUpdatedGroup(
    rootGroup: RootGroup,
    groupId: PathId,
    groupUpdate: raml.GroupUpdate,
    newVersion: Timestamp)(implicit identity: Identity, authorizer: Authorizer, executionContext: ExecutionContext): Future[RootGroup] = async {
    val group = rootGroup.group(groupId).getOrElse(Group.empty(groupId))

    /**
      * roll back to a previous group version
      */
    def revertToOlderVersion: Future[Option[RootGroup]] = groupUpdate.version match {
      case Some(version) =>
        val targetVersion = Timestamp(version)
        checkAuthorizationOrThrow(UpdateGroup, group)
        groupManager.group(group.id, targetVersion)
          .filter(checkAuthorizationOrThrow(ViewGroup, _))
          .map(g => g.getOrElse(throw new IllegalArgumentException(s"Group ${group.id} not available in version $targetVersion")))
          .map(rootGroup.putGroup(_, newVersion))
          .map(Some(_))
      case None => Future.successful(None)
    }

    def scaleChange: Option[RootGroup] = groupUpdate.scaleBy.map { scale =>
      checkAuthorizationOrThrow(UpdateGroup, group)
      rootGroup.updateTransitiveApps(group.id, app => app.copy(instances = (app.instances * scale).ceil.toInt), newVersion)
    }

    def createOrUpdateChange: RootGroup = {
      // groupManager.update always passes a group, even if it doesn't exist
      val maybeExistingGroup = groupManager.group(group.id)
      val appConversionFunc: (raml.App => AppDefinition) = Raml.fromRaml[raml.App, AppDefinition]
      val updatedGroup: Group = Raml.fromRaml(
        GroupConversion(groupUpdate, group, newVersion) -> appConversionFunc)

      maybeExistingGroup.fold(checkAuthorizationOrThrow(CreateRunSpec, updatedGroup))(checkAuthorizationOrThrow(UpdateGroup, _))

      rootGroup.putGroup(updatedGroup, newVersion)
    }

    await(revertToOlderVersion)
      .orElse(scaleChange)
      .getOrElse(createOrUpdateChange)
  }

  private def checkAuthorizationOrThrow[Resource](action: AuthorizedAction[Resource], resource: Resource)(implicit identity: Identity, authorizer: Authorizer): Boolean = {
    if (!authorizer.isAuthorized(identity, action, resource))
      throw AccessDeniedException()
    else
      true
  }
}
