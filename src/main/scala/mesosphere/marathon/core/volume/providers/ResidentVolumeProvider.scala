package mesosphere.marathon.core.volume.providers

import com.wix.accord.Validator
import com.wix.accord.combinators.NilValidator
import com.wix.accord.dsl._
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._

/**
  * ResidentVolumeProvider handles persistent volumes allocated from agent resources.
  */
protected[volume] case object ResidentVolumeProvider
    extends AbstractPersistentVolumeProvider("resident") {

  import mesosphere.marathon.api.v2.Validation._
  import org.apache.mesos.Protos.Volume.Mode

  // no provider-specific rules at the app level
  // TODO(jdef) could/should refactor resident validation from AppDefinition to here
  val appValidation: Validator[AppDefinition] = new NilValidator[AppDefinition]

  // no provider-specific rules at the group level
  val groupValidation: Validator[Group] = new NilValidator[Group]

  val volumeValidation = validator[PersistentVolume] { v =>
    v.persistent.size is notEmpty
    v.mode is equalTo(Mode.RW)
    //persistent volumes require those CLI parameters provided
    v is configValueSet("mesos_authentication_principal", "mesos_role", "mesos_authentication_secret_file")
  }

  override def accepts(volume: PersistentVolume): Boolean = {
    // this should also match if the providerName is not set. By definition a persistent volume
    // without a providerName is a local resident volume.
    volume.persistent.providerName.getOrElse(name) == name
  }

  val containerInjector = new ContainerInjector[Volume] {}
  val commandInjector = new CommandInjector[PersistentVolume] {}
}
