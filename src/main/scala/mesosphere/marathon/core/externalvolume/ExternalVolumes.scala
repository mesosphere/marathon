package mesosphere.marathon
package core.externalvolume

import com.wix.accord._
import mesosphere.marathon.core.externalvolume.impl._
import mesosphere.marathon.raml.AppVolume
import mesosphere.marathon.state._
import org.apache.mesos.Protos.ContainerInfo

/**
  * API facade for callers interested in storage volumes
  */
object ExternalVolumes {
  private[this] lazy val providers: ExternalVolumeProviderRegistry = StaticExternalVolumeProviderRegistry

  def build(builder: ContainerInfo.Builder, v: ExternalVolume): Unit = {
    providers.get(v.external.provider).foreach { _.build(builder, v) }
  }

  def validExternalVolume: Validator[ExternalVolume] = new Validator[ExternalVolume] {
    def apply(ev: ExternalVolume) = providers.get(ev.external.provider) match {
      case Some(p) => p.validations.volume(ev)
      case None => Failure(Set(RuleViolation(None, "is unknown provider", Some("external/provider"))))
    }
  }

  def validRamlVolume(container: raml.Container): Validator[AppVolume] = new Validator[AppVolume] {
    def apply(ev: AppVolume) = ev.external.flatMap(e => e.provider.flatMap(providers.get)) match {
      case Some(p) =>
        validate(ev)(p.validations.ramlVolume(container))
      case None =>
        Failure(Set(RuleViolation(None, "is unknown provider", Some("external/provider"))))
    }
  }

  /** @return a validator that checks the validity of a container given the related volume providers */
  def validApp(): Validator[AppDefinition] = new Validator[AppDefinition] {
    override def apply(app: AppDefinition): Result = {
      val appProviders: Set[ExternalVolumeProvider] =
        app.externalVolumes.flatMap(ev => providers.get(ev.external.provider))(collection.breakOut)
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
          _.volumes.flatMap(_.external.flatMap(_.provider))(collection.breakOut)
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
      providers.all.map { provider =>
        validate(rootGroup)(provider.validations.rootGroup)
      }.fold(Success)(_ and _)
  }
}
