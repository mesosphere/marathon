package mesosphere.marathon.plugin.plugin

/**
  * Opt functions generally perform some mutable operation on some arg of
  * type T and possibly return an "undo" Opt implementation.
  */
trait Opt[T] extends Function1[T, Option[Opt[T]]]

object Opt {
  /**
    * OptFactory functions consume some parameter P and generate a functional
    * configuration option for type T.
    */
  trait Factory[P, T] extends Function1[P, Option[Opt[T]]]

  object Factory {
    /** @return a factory instance that combines the effect of the given factories */
    def combine[P, T](f: Factory[P, T]*): Option[Factory[P, T]] = {
      if (f.isEmpty) None
      else if (f.size == 1) f.headOption
      else Some(new Factory[P, T] {
        override def apply(p: P): Option[Opt[T]] = {
          val opts = f.map(_(p)).flatten
          if (opts.isEmpty) None else Opt.combine(opts: _*)
        }
      })
    }

    /** Plugin represents a pluggable Opt.Factory */
    trait Plugin[P, T] extends Factory[P, T] with mesosphere.marathon.plugin.plugin.Plugin
  }

  /**
    * applyAll invokes each provided Opt, in order, on the given `t`
    * @return the result of the last invoked Opt, or else a no-op Opt
    */
  def applyAll[T](t: T, opts: Opt[T]*): Option[Opt[T]] = {
    var last: Option[Opt[T]] = None
    for (o <- opts) {
      last = o(t)
    }
    last
  }

  /**
    * combine generates a single functional option that is the equivalent of invoking all of the
    * given opts, in the order they're given here.
    */
  def combine[T](opts: Opt[T]*): Option[Opt[T]] = {
    if (opts.isEmpty) None
    else if (opts.size == 1) opts.headOption
    else Some(new Opt[T] {
      override def apply(t: T): Option[Opt[T]] = {
        applyAll(t, opts: _*)
      }
    })
  }
}
