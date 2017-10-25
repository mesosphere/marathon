package mesosphere.marathon

package object raml extends RamlConversions {

  private[raml] implicit class AnyToRaml[A](val a: A) extends AnyVal {
    def toRaml[B](implicit writes: Writes[A, B]): B = {
      writes.write(a)
    }
  }
}
