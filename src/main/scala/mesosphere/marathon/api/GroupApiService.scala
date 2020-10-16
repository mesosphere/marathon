package mesosphere.marathon
package api

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.{GroupConversion, Raml}
import mesosphere.marathon.state._

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}

class GroupApiService(groupManager: GroupManager)(implicit
    authorizer: Authorizer,
    executionContext: ExecutionContext
) extends StrictLogging {

  /**
    * Encapsulates the group update logic that is following:
    * - if version is set, group is reverted to that version
    * - if scaleBy is set, group is scaled up
    * - if neither a version nor scaleBy is set then the group is updated
    */
  def updateGroup(rootGroup: RootGroup, groupId: AbsolutePathId, groupUpdate: raml.GroupUpdate, newVersion: Timestamp)(implicit
      identity: Identity
  ): Future[RootGroup] =
    async {
      val currentGroup = rootGroup.group(groupId).getOrElse(Group.empty(groupId))
      checkAuthorizationOrThrow(UpdateGroup, currentGroup)

      /**
        * roll back to a previous group version
        */
      def revertToOlderVersion: Future[Option[RootGroup]] =
        groupUpdate.version match {
          case Some(version) =>
            val targetVersion = Timestamp(version)
            groupManager
              .group(currentGroup.id, targetVersion)
              .map(_.getOrElse(throw new IllegalArgumentException(s"Group ${currentGroup.id} not available in version $targetVersion")))
              .filter(checkAuthorizationOrThrow(ViewGroup, _))
              .map(g => Some(rootGroup.putGroup(g, newVersion)))
          case None => Future.successful(None)
        }

      def scaleChange: Option[RootGroup] =
        groupUpdate.scaleBy.map { scale =>
          rootGroup.updateTransitiveApps(currentGroup.id, app => app.copy(instances = (app.instances * scale).ceil.toInt), newVersion)
        }

      def createOrUpdateChange: RootGroup = {
        // groupManager.update always passes a group, even if it doesn't exist
        val maybeExistingGroup = groupManager.group(currentGroup.id)
        val appConversionFunc: (raml.App => AppDefinition) = Raml.fromRaml[raml.App, AppDefinition]
        val updatedGroup: Group = GroupConversion(groupUpdate, currentGroup, newVersion).apply(appConversionFunc)

        if (maybeExistingGroup.isEmpty) checkAuthorizationOrThrow(CreateGroup, updatedGroup)

        rootGroup.putGroup(updatedGroup, newVersion)
      }

      await(revertToOlderVersion)
        .orElse(scaleChange)
        .getOrElse(createOrUpdateChange)
    }

  private def checkAuthorizationOrThrow[Resource](action: AuthorizedAction[Resource], resource: Resource)(implicit
      identity: Identity,
      authorizer: Authorizer
  ): Boolean = {
    if (!authorizer.isAuthorized(identity, action, resource))
      throw RejectionException(Rejection.AccessDeniedRejection(authorizer, identity))
    else
      true
  }
}
