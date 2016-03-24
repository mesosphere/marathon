package mesosphere.marathon.core.volume

import mesosphere.marathon.state.Volume
import org.apache.mesos.Protos.{ CommandInfo, ContainerInfo }

/**
  * BuilderContext captures additional metadata required for decorating mesos Task protobufs with
  * metadata pertaining to volumes
  */
sealed trait BuilderContext

/**
  * ContainerContext captures the builder that generates a Task's ContainerInfo
  */
final case class ContainerContext(ci: ContainerInfo.Builder) extends BuilderContext
/**
  * CommandContext captures the builder that generates a Task's CommandInfo
  */
final case class CommandContext(ct: ContainerInfo.Type, ci: CommandInfo.Builder) extends BuilderContext

/**
  * ContextUpdate implementations decorate Context with additional metadata for a given Volume.
  */
trait ContextUpdate {
  /**
    * Generates an updated BuilderContext for the given input context `c` and volume `v`.
    * @param c is the initial input BuilderContext
    * @param v is the volume that provides metadata with which the initial context is decorated
    * @return a BuilderContext decorated with metadata from volume `v`; None if the context is not
    *  supported by the storage provider associated with the given volume.
    */
  protected def updated[C <: BuilderContext](c: C, v: Volume): Option[C] = None

  /**
    * Generates an updated [[BuilderContext]] for the given volumes. For subclasses that don't support
    * a particular BuilderContext type Some(initialContext()) is returned.
    * @param volumes are the storage volumes that provide metadata to be captured in BuilderContext
    * @param initialContext generates the initial BuilderContext to be decorated with volume metadata, it
    *  will not be invoked if there are no volumes.
    * @return None if there are no volumes, otherwise returns Some(BuilderContext).
    */
  final def apply[C <: BuilderContext](volumes: Iterable[Volume])(initialContext: () => C): Option[C] = {
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
  override protected def updated[C <: BuilderContext](ci: C, v: Volume): Option[C] =
    VolumesModule.providers(v).filter(_.isInstanceOf[ContextUpdate]).
      map(_.asInstanceOf[ContextUpdate]).
      flatMap(_.updated(ci, v))
}
