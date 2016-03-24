package mesosphere.marathon.core.volume

import mesosphere.marathon.state.Volume
import org.apache.mesos.Protos.{ CommandInfo, ContainerInfo }

/**
  * Context captures additional metadata required for decorating mesos Task protobufs with
  * metadata pertaining to volumes
  */
sealed trait Context

/**
  * ContainerContext captures the builder that generates a Task's ContainerInfo
  */
final case class ContainerContext(ci: ContainerInfo.Builder) extends Context
/**
  * CommandContext captures the builder that generates a Task's CommandInfo
  */
final case class CommandContext(ct: ContainerInfo.Type, ci: CommandInfo.Builder) extends Context

/**
  * ContextUpdate implementations decorate Context with additional metadata for a given Volume.
  */
trait ContextUpdate {
  /**
    * Generate an updated ContainerInfo (of ContainerContext) for the given volume
    * @return an updated ContainerContext or None if ContainerContext is unsupported
    */
  protected def updatedContainer(ci: ContainerContext, v: Volume): Option[ContainerContext] = None
  /**
    * Generate an updated CommandInfo (of CommandContext) for the given volume
    * @return an updated CommandContext or None if CommandContext is unsupported
    */
  protected def updatedCommand(cm: CommandContext, v: Volume): Option[CommandContext] = None

  /**
    * Generates an updated Context for the given input context `c` and volume `v`.
    * @param c is the initial input Context
    * @param v is the volume that provides metadata with which the initial context is decorated
    * @return a Context decorated with metadata from volume `v`; None if the context is not
    *  supported by the storage provider associated with the given volume.
    */
  final def updated[C <: Context](c: C, v: Volume): Option[C] = {
    c match {
      case ctx: ContainerContext => updatedContainer(ctx, v).asInstanceOf[Option[C]]
      case ctx: CommandContext   => updatedCommand(ctx, v).asInstanceOf[Option[C]]
      case _                     => throw new Error(s"unknown context ${c}")
    }
  }

  /**
    * Generates an updated [[Context]] for the given volumes. For subclasses that don't support
    * a particular Context type Some(initialContext()) is returned.
    * @param volumes are the storage volumes that provide metadata to be captured in Context
    * @param initialContext generates the initial Context to be decorated with volume metadata, it
    *  will not be invoked if there are no volumes.
    * @return None if there are no volumes, otherwise returns Some(Context).
    */
  final def apply[C <: Context](volumes: Iterable[Volume])(initialContext: () => C): Option[C] = {
    if (volumes.isEmpty) None
    else {
      var cc = initialContext()
      volumes.foreach { vol => cc = updated(cc, vol).getOrElse(cc) }
      Some(cc)
    }
  }
}

/**
  * ContextUpdate (companion) routes update calls to the appropriate volume provider.
  */
object ContextUpdate extends ContextUpdate {
  override protected def updatedContainer(ci: ContainerContext, v: Volume): Option[ContainerContext] =
    VolumesModule.providers(v).filter(_.isInstanceOf[ContextUpdate]).
      map(_.asInstanceOf[ContextUpdate]).
      flatMap(_.updatedContainer(ci, v))

  override protected def updatedCommand(cm: CommandContext, v: Volume): Option[CommandContext] =
    VolumesModule.providers(v).filter(_.isInstanceOf[ContextUpdate]).
      map(_.asInstanceOf[ContextUpdate]).
      flatMap(_.updatedCommand(cm, v))
}
