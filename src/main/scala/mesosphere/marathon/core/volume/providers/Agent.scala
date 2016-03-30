package mesosphere.marathon.core.volume.providers

import com.wix.accord.Validator
import com.wix.accord.combinators.NilValidator
import com.wix.accord.dsl._
import com.wix.accord.Validator
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._

/**
  * AgentVolumeProvider handles persistent volumes allocated from agent resources.
  */
protected[volume] case object AgentVolumeProvider extends PersistentVolumeProvider with LocalVolumes {
  import org.apache.mesos.Protos.Volume.Mode
  import mesosphere.marathon.api.v2.Validation._

  /** this is the name of the agent volume provider */
  val name = "agent"

  // no provider-specific rules at the container level
  val containerValidation: Validator[Container] = new NilValidator[Container]

  // no provider-specific rules at the group level
  val groupValidation: Validator[Group] = new NilValidator[Group]

  val validPersistentVolume = validator[PersistentVolume] { v =>
    v.persistent.size is notEmpty
    v.mode is equalTo(Mode.RW)
    //persistent volumes require those CLI parameters provided
    v is configValueSet("mesos_authentication_principal", "mesos_role", "mesos_authentication_secret_file")
  }

  override def accepts(volume: PersistentVolume): Boolean = {
    // this should also match if the providerName is not set. By definition a persistent volume
    // without a providerName is a local agent volume.
    volume.persistent.providerName.getOrElse(name) == name
  }
}
