package mesosphere.marathon
package core.externalvolume

import com.wix.accord._
import mesosphere.marathon.core.externalvolume.impl._
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

  /** @return a validator that checks the validity of a container given the related volume providers */
  def validApp(): Validator[AppDefinition] = new Validator[AppDefinition] {
    def apply(app: AppDefinition) = {
      val appProviders: Set[ExternalVolumeProvider] =
        app.externalVolumes.flatMap(ev => providers.get(ev.external.provider))(collection.breakOut)
      appProviders.map { provider =>
        validate(app)(provider.validations.app)
      }.fold(Success)(_ and _)
    }
  }

  /** @return a validator that checks the validity of a group given the related volume providers */
  def validRootGroup(): Validator[RootGroup] = new Validator[RootGroup] {
    def apply(rootGroup: RootGroup) =
      providers.all.map { provider =>
        validate(rootGroup)(provider.validations.rootGroup)
      }.fold(Success)(_ and _)
  }
}
