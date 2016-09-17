package mesosphere.marathon.raml

trait Reads[A <: RamlGenerated, +B] {
  final def apply(raml: A): B = read(raml)
  def read(raml: A): B
}

object Reads {
  def apply[A <: RamlGenerated, B](reads: A => B): Reads[A, B] =
    new Reads[A, B] {
      override def read(raml: A): B = reads(raml)
    }
}

trait Writes[-A, B <: RamlGenerated] {
  final def apply(a: A): B = write(a)
  def write(a: A): B
}

object Writes {
  def apply[A, B <: RamlGenerated](writes: A => B): Writes[A, B] =
    new Writes[A, B] {
      override def write(a: A): B = writes(a)
    }
}

object Raml {
  def toRaml[A, B <: RamlGenerated](o: A)(implicit writes: Writes[A, B]): B = writes(o)
  def fromRaml[A <: RamlGenerated, B](raml: A)(implicit reads: Reads[A, B]): B = reads(raml)
}

