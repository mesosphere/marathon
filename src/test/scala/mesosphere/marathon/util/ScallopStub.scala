package mesosphere.marathon
package util

import org.rogach.scallop.ScallopOption

class ScallopStub[A](name: String, value: Option[A]) extends ScallopOption[A](name) {
  override def get = value
  override def apply() = value.get
}

object ScallopStub {
  def apply[A](value: Option[A]): ScallopStub[A] = new ScallopStub("", value)
  def apply[A](name: String, value: Option[A]): ScallopStub[A] = new ScallopStub(name, value)
}
