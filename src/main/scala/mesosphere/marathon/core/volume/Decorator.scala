package mesosphere.marathon.core.volume

import mesosphere.marathon.state.Volume
import org.apache.mesos.Protos.{ CommandInfo, ContainerInfo }

/**
  * BuilderContext captures additional metadata required for decorating mesos Task protobufs with
  * metadata pertaining to volumes
  */
abstract class DecoratorContext

/**
  * ContainerContext captures the builder that generates a Task's ContainerInfo
  */
final case class ContainerContext(ci: ContainerInfo.Builder) extends DecoratorContext
/**
  * CommandContext captures the builder that generates a Task's CommandInfo
  */
final case class CommandContext(ct: ContainerInfo.Type, ci: CommandInfo.Builder) extends DecoratorContext

/**
  * Decorator implementations decorate DecoratorContexts with additional metadata for a given Volume.
  */
trait Decorator {
  /**
    * Generates an updated DecoratorContext for the given input context `c` and volume `v`.
    *
    * @param c is the initial input DecoratorContext
    * @param v is the volume that provides metadata with which the initial context is decorated
    * @return a DecoratorContext decorated with metadata from volume `v`; None if the context is not
    *  supported by the storage provider associated with the given volume.
    */
  protected def decorated[C <: DecoratorContext](ctx: C, v: Volume): C = ctx

  /**
    * Generates an updated [[DecoratorContext]] for the given volumes. For subclasses that don't support
    * a particular DecoratorContext the context is return unchanged.
    *
    * @param volumes are the storage volumes that provide metadata to be captured in DecoratorContext
    * @param ctx will be decorated with volume metadata
    * @return the decorated context.
    */
  final def apply[C <: DecoratorContext](volumes: Iterable[Volume], ctx: C): C = {
    volumes.foldLeft[C](ctx)(decorated[C])
  }
}

/**
  * Decorator (companion) routes update calls to the appropriate volume provider.
  */
object Decorator extends Decorator {
  override protected def decorated[C <: DecoratorContext](ctx: C, v: Volume): C =
    VolumesModule.providers(v).foldLeft(ctx){ (ctx, d) => d.decorated(ctx, v) }
}
