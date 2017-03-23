package mesosphere.mesos.protos

import scala.collection.immutable.NumericRange

case class Range(
    begin: Long,
    end: Long) {

  def asScala(): NumericRange[Long] = {
    begin to end
  }
}
