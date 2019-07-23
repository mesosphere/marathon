package mesosphere.marathon
package raml

trait Reads[A] {
  def fromRaml(): A
}

trait Writes[A] {
  def toRaml(): A
}
