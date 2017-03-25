package mesosphere.marathon
package core.externalvolume.impl

import com.wix.accord.Validator
import mesosphere.marathon.state.{ ExternalVolume, AppDefinition, RootGroup }
import org.apache.mesos.Protos.ContainerInfo

/**
  * Validations for external volumes on different levels.
  */
private[externalvolume] trait ExternalVolumeValidations {
  def rootGroup: Validator[RootGroup]
  def app: Validator[AppDefinition]
  def volume: Validator[ExternalVolume]
  def ramlVolume(container: raml.Container): Validator[raml.AppVolume]
  def ramlApp: Validator[raml.App]
}

/**
  * ExternalVolumeProvider is an interface implemented by external storage volume providers
  */
private[externalvolume] trait ExternalVolumeProvider {
  def name: String

  def validations: ExternalVolumeValidations

  /** build adds v to the given builder **/
  def build(builder: ContainerInfo.Builder, v: ExternalVolume): Unit
}
