package mesosphere.marathon

package object raml extends RamlConversions {

  implicit class AnyToRaml[A](val a: A) extends AnyVal {
    def toRaml[B](implicit writes: Writes[A, B]): B = {
      writes.write(a)
    }
  }

  implicit class AnyFromRaml[A](val a: A) extends AnyVal {
    def fromRaml[B](implicit reads: Reads[A, B]): B = {
      reads.read(a)
    }
  }
}
