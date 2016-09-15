package mesosphere.marathon.api.v2.conversion

trait Converter[A, B] {
  def convert(a: A): B
}

object Converter {
  def apply[A,B](a: A)(implicit c: Converter[A,B]): B = c.convert(a)

  def apply[A, B](f: (A) => B): Converter[A,B] = new Converter[A,B] {
    override def convert(a: A): B = f(a)
  }
}
