package mesosphere.marathon.plugin.optfactory

import mesosphere.marathon.plugin.AppDefinition
import mesosphere.marathon.plugin.plugin._

/** AppOptFactory generates functional options that configure objects of type T */
trait AppOptFactory[T] extends Opt.Factory[AppDefinition, T] with Plugin

object AppOptFactory {
  def noop[T]: AppOptFactory[T] = new AppOptFactory[T] {
    override def apply(p: AppDefinition): Option[Opt[T]] = Some(Opt.noop)
  }
  def combine[T](f: AppOptFactory[T]*): AppOptFactory[T] = new AppOptFactory[T] {
    override def apply(p: AppDefinition): Option[Opt[T]] = {
      val opts = f.map(_(p)).flatten
      if (opts.isEmpty) None else Some(Opt.combine(opts: _*))
    }
  }
}
