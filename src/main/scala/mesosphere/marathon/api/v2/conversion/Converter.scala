package mesosphere.marathon.api.v2.conversion

trait Converter[A,B] {
  def convert(a: A): Option[B]
}

object Converter {
  def convert[A,B](a: A)(implicit c: Converter[A,B]): Option[B] = c.convert(a)

  def apply[A,B](f: (A) => Option[B]): Converter[A,B] = new Converter[A,B] {
    override def convert(a: A): Option[B] = f(a)
  }
}
