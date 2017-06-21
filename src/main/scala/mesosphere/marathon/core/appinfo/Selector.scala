package mesosphere.marathon
package core.appinfo

trait Selector[A] {

  def matches(a: A): Boolean
}

object Selector {

  def apply[A](f: A => Boolean): Selector[A] = new Selector[A] {
    override def matches(a: A): Boolean = f(a)
  }

  def all[A]: Selector[A] = Selector[A] { _ => true }

  def none[A]: Selector[A] = Selector[A] { _ => false }

  def forall[A](it: Seq[Selector[A]]): Selector[A] = new Selector[A] {
    override def matches(a: A): Boolean = it.forall(_.matches(a))
  }
}
