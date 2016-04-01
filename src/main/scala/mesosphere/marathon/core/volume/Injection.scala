package mesosphere.marathon.core.volume

import mesosphere.marathon.state.Volume
import org.apache.mesos.Protos.{ CommandInfo, ContainerInfo }

/**
  * InjectionContext captures additional metadata required for decorating mesos Task protobufs with
  * metadata pertaining to volumes
  */
sealed trait InjectionContext

/**
  * ContainerContext captures the builder that generates a Task's ContainerInfo
  */
final case class ContainerContext(container: ContainerInfo.Builder) extends InjectionContext
/**
  * CommandContext captures the builder that generates a Task's CommandInfo
  */
final case class CommandContext(containerType: ContainerInfo.Type, command: CommandInfo.Builder)
  extends InjectionContext

/**
  * VolumeInjector implementations inject InjectionContext's with additional metadata for a given Volume.
  */
trait VolumeInjector[V <: Volume, C <: InjectionContext] {
  /**
    * Generates an updated InjectionContext for the given input context `c` and volume `v`.
 *
    * @param c is the initial input InjectionContext
    * @param v is the volume that provides metadata with which the initial context is decorated
    * @return a InjectionContext decorated with metadata from volume `v`; None if the context is not
    *  supported by the storage provider associated with the given volume.
    */
  protected[volume] def inject(c: C, v: V): C = c

  /**
    * Generates an updated [[InjectionContext]] for the given volumes. For subclasses that don't support
    * a particular InjectionContext type Some(initialContext()) is returned.
    * @param volumes are the storage volumes that provide metadata to be captured in InjectionContext
    * @param c generates the initial InjectionContext to be decorated with volume metadata, it
    *  will not be invoked if there are no volumes.
    * @return None if there are no volumes, otherwise returns Some(InjectionContext).
    */
  final def apply(volumes: Iterable[V], c: C): C = {
    volumes.foldLeft[C](c)(inject)
  }
}

trait ContainerInjector[V <: Volume] extends VolumeInjector[V, ContainerContext]
trait CommandInjector[V <: Volume] extends VolumeInjector[V, CommandContext]

