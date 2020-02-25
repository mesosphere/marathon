package mesosphere.marathon
package core.externalvolume

import com.wix.accord.Descriptions.{Explicit, Path}
import com.wix.accord._
import mesosphere.marathon.core.externalvolume.impl._
import mesosphere.marathon.core.externalvolume.impl.providers.DVDIProvider
import mesosphere.marathon.raml.AppExternalVolume
import mesosphere.marathon.state._
import org.apache.mesos.Protos

/**
  * API facade for callers interested in storage volumes
  */
object ExternalVolumes {
  private[this] lazy val providers: Map[String, ExternalVolumeProvider] =
    Map(DVDIProvider.name -> DVDIProvider)

  def build(v: ExternalVolume, mount: VolumeMount): Option[Protos.Volume] = {
    providers.get(v.external.provider).map { _.build(v, mount) }
  }

  def validExternalVolume: Validator[ExternalVolume] = new Validator[ExternalVolume] {
    def apply(ev: ExternalVolume) = providers.get(ev.external.provider) match {
      case Some(p) => p.validations.volume(ev)
      case None => Failure(Set(
        RuleViolation(None, "is unknown provider", Path(Explicit("external"), Explicit("provider")))))
    }
  }

  def validRamlVolume(container: raml.Container): Validator[AppExternalVolume] = new Validator[AppExternalVolume] {
    def apply(ev: AppExternalVolume) = ev.external.provider.flatMap(providers.get(_)) match {
      case Some(p) =>
        validate(ev)(p.validations.ramlVolume(container))
      case None =>
        Failure(Set(RuleViolation(None, "is unknown provider", Path(Explicit("external"), Explicit("provider")))))
    }
  }

  /** @return a validator that checks the validity of a container given the related volume providers */
  def validApp(): Validator[AppDefinition] = new Validator[AppDefinition] {
    override def apply(app: AppDefinition): Result = {
      val appProviders: Set[ExternalVolumeProvider] =
        app.externalVolumes.iterator.flatMap(ev => providers.get(ev.external.provider)).toSet
      appProviders.map { provider =>
        validate(app)(provider.validations.app)
      }.fold(Success)(_ and _)
    }
  }

  /** @return a validator that checks the validity of a container given the related volume providers */
  def validAppRaml(): Validator[raml.App] = new Validator[raml.App] {
    override def apply(app: raml.App): Result = {
      val appProviders: Set[ExternalVolumeProvider] = {
        val wantedProviders: Set[String] = app.container.fold(Set.empty[String]) {
          _.volumes.collect{ case v: AppExternalVolume => v }.iterator.flatMap(_.external.provider).toSet
        }
        wantedProviders.flatMap(wanted => providers.get(wanted))
      }
      appProviders.map { provider =>
        validate(app)(provider.validations.ramlApp)
      }.fold(Success)(_ and _)
    }
  }

  /** @return a validator that checks the validity of a group given the related volume providers */
  def validRootGroup(): Validator[RootGroup] = new Validator[RootGroup] {
    override def apply(rootGroup: RootGroup): Result =
      providers.values.map { provider =>
        validate(rootGroup)(provider.validations.rootGroup)
      }.fold(Success)(_ and _)
  }
}
