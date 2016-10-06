package mesosphere.marathon.core.appinfo

trait Selector[A] {

  def matches(a: A): Boolean
}

object Selector {

  def apply[A](f: A => Boolean): Selector[A] = new Selector[A] {
    override def matches(a: A): Boolean = f(a)
  }

  def all[A]: Selector[A] = Selector[A] { _ => true }

  def none[A]: Selector[A] = Selector[A] { _ => false }

  def forall[A](it: Iterable[Selector[A]]): Selector[A] = new Selector[A] {
    override def matches(a: A): Boolean = it.forall(_.matches(a))
  }
}
