package mesosphere.marathon.plugin.optfactory

import mesosphere.marathon.plugin.AppDefinition
import mesosphere.marathon.plugin.plugin._

/** AppOptFactory generates functional options that configure objects of type T */
trait AppOptFactory[T] extends Opt.Factory[AppDefinition, T] with Plugin

object AppOptFactory {
  // TODO(jdef) I'd love to make this more generic/reusable; hitting my scala limits though
  def combine[T](f: AppOptFactory[T]*): Option[AppOptFactory[T]] = {
    if (f.isEmpty) None
    else Some(new AppOptFactory[T] {
      override def apply(p: AppDefinition): Option[Opt[T]] = {
        val opts = f.map(_(p)).flatten
        if (opts.isEmpty) None else Some(Opt.combine(opts: _*))
      }
    })
  }
}
