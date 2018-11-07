package mesosphere.marathon

trait Normalization[T] extends AnyRef {
  def normalized(t: T): T
  def apply(n: Normalization[T]): Normalization[T] = Normalization { t => n.normalized(normalized(t)) }
}

/**
  * Normalization which takes context together with a parameter.
  * @tparam T
  * @tparam C
  */
trait NormalizationWithContext[T, C] extends Normalization[T] {
  def normalizedWithContext(t: T, context: C): T
  def applyNormalization(n: Normalization[T]): NormalizationWithContext[T, C] = Normalization.withContext { (t, maybeContext) =>

    maybeContext.map { c =>
      n.normalized(normalizedWithContext(t, c))
    }.getOrElse {
      n.normalized(normalized(t))
    }
  }
}

object Normalization {

  implicit class Normalized[T](val a: T) extends AnyVal {
    def normalize(implicit f: Normalization[T]): T = f.normalized(a)
    def normalizeWithContext[C](c: C)(implicit f: NormalizationWithContext[T, C]): T = f.normalizedWithContext(a, c)
  }

  def apply[T](f: (T => T)): Normalization[T] = (t: T) => f(t)

  def withContext[T, C](f: (T, Option[C]) => T): NormalizationWithContext[T, C] = new NormalizationWithContext[T, C] {

    override def normalizedWithContext(t: T, context: C): T = f(t, Some(context))

    override def normalized(t: T): T = f(t, None)
  }

}
