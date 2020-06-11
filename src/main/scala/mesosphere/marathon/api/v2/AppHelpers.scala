package mesosphere.marathon
package api.v2

import com.wix.accord.Validator
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.api.{Rejection, RejectionException}
import mesosphere.marathon.core.appinfo.{AppSelector, Selector}
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.raml.{AppConversion, AppExternalVolume, AppPersistentVolume, Raml}
import mesosphere.marathon.state.VersionInfo.OnlyVersion
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition, Timestamp, UnreachableStrategy}
import mesosphere.marathon.stream.Implicits.toRichIterable

object AppHelpers {

  def appNormalization(config: AppNormalization.Config, validRoles: Set[String]): Normalization[raml.App] = Normalization { app =>

    validateOrThrow(app)(AppValidation.validateOldAppAPI)

    val migrated = AppNormalization.forDeprecated(config).normalized(app)
    val preNormalized = AppNormalization.forPreValidation(config).normalized(migrated)
    validateOrThrow(preNormalized)(AppValidation.validateCanonicalAppAPI(config.enabledFeatures, () => config.defaultNetworkName, validRoles))
    AppNormalization.forPostValidation(config).normalized(preNormalized)
  }

  def appUpdateNormalization(config: AppNormalization.Config): Normalization[raml.AppUpdate] = Normalization { app =>
    val migrated = AppNormalization.forDeprecatedUpdates(config).normalized(app)
    validateOrThrow(migrated)(AppValidation.validateAppUpdateVersion)
    AppNormalization.forUpdates(config).normalized(migrated)
  }

  /**
    * Create an App from an AppUpdate. This basically applies when someone uses our API to create apps
    * using the `PUT` method: an AppUpdate is submitted for an App that doesn't actually exist: we convert the
    * "update" operation into a "create" operation. This helper func facilitates that.
    */
  def withoutPriorAppDefinition(update: raml.AppUpdate, appId: AbsolutePathId): raml.App = {
    val selectedStrategy = AppConversion.UpgradeStrategyConverter(
      upgradeStrategy = update.upgradeStrategy.map(Raml.fromRaml(_)),
      hasPersistentVolumes = update.container.exists(_.volumes.existsAn[AppPersistentVolume]),
      hasExternalVolumes = update.container.exists(_.volumes.existsAn[AppExternalVolume])
    )
    val hasPersistentVols = update.container.exists(_.volumes.existsAn[AppPersistentVolume])
    val unreachableStrategy = update
      .unreachableStrategy.map(Raml.fromRaml(_))
      .getOrElse(UnreachableStrategy.default(hasPersistentVols))

    // We're using orNull here, as the AppDefinition is only used as a template to create an raml.App from
    // If we don't have a role in the AppUpdate, we get an Empty role in the raml.App, which then gets defaulted
    // in the normalization
    val role = update.role.orNull

    val template = AppDefinition(
      appId, role = role, upgradeStrategy = selectedStrategy, unreachableStrategy = unreachableStrategy)
    Raml.fromRaml(update -> template)
  }

  def authzSelector(implicit authz: Authorizer, identity: Identity): AppSelector = Selector[AppDefinition] { app =>
    authz.isAuthorized(identity, ViewRunSpec, app)
  }

  private def checkAuthorization[A, B >: A](action: AuthorizedAction[B], resource: A)(implicit identity: Identity, authorizer: Authorizer): A = {
    if (authorizer.isAuthorized(identity, action, resource)) resource
    else throw RejectionException(Rejection.AccessDeniedRejection(authorizer, identity))
  }

  /**
    * Throws one of the following:
    *
    * - [[mesosphere.marathon.ValidationFailedException]]
    * - [[mesosphere.marathon.AppNotFoundException]]
    * - [[mesosphere.marathon.api.RejectionException]] - AccessDeniedRejection(...)
    *
    * TODO - move async concern out
    */
  def updateOrCreate(
    appId: AbsolutePathId,
    existing: Option[AppDefinition],
    appUpdate: raml.AppUpdate,
    partialUpdate: Boolean,
    allowCreation: Boolean,
    now: Timestamp,
    service: MarathonSchedulerService)(implicit
    identity: Identity,
    authorizer: Authorizer,
    appDefinitionValidator: Validator[AppDefinition],
    appNormalization: Normalization[raml.App]): AppDefinition = {
    import Normalization._
    def createApp(): AppDefinition = {
      val app = withoutPriorAppDefinition(appUpdate, appId).normalize
      // versionInfo doesn't change - it's never overridden by an AppUpdate.
      // the call to fromRaml loses the original versionInfo; it's just the current time in this case
      // so we just query for that (using a more predictable clock than AppDefinition has access to)
      val appDef = validateOrThrow(Raml.fromRaml(app).copy(versionInfo = OnlyVersion(now)))
      checkAuthorization(CreateRunSpec, appDef)
    }

    def updateApp(current: AppDefinition): AppDefinition = {
      val app =
        if (partialUpdate)
          Raml.fromRaml(appUpdate -> current).normalize
        else
          withoutPriorAppDefinition(appUpdate, appId).normalize

      // versionInfo doesn't change - it's never overridden by an AppUpdate.
      // the call to fromRaml loses the original versionInfo; we take special care to preserve it
      val appDef = validateOrThrow(Raml.fromRaml(app).copy(versionInfo = current.versionInfo))
      checkAuthorization(UpdateRunSpec, appDef)
    }

    def rollback(current: AppDefinition, version: Timestamp): AppDefinition = {
      val app = service.getApp(appId, version).getOrElse(throw AppNotFoundException(appId))
      checkAuthorization(ViewRunSpec, app)
      checkAuthorization(UpdateRunSpec, current)
      app
    }

    def updateOrRollback(current: AppDefinition): AppDefinition = appUpdate.version
      .map(v => rollback(current, Timestamp(v)))
      .getOrElse(updateApp(current))

    existing match {
      case Some(app) =>
        // we can only rollback existing apps because we deleted all old versions when dropping an app
        updateOrRollback(app)
      case None if allowCreation =>
        createApp()
      case None =>
        throw AppNotFoundException(appId)
    }
  }
}
