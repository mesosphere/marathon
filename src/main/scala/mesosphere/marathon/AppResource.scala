package mesosphere.marathon

import org.apache.mesos.Protos.Offer
import scala.collection.JavaConverters._
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.mesos.TaskBuilder

/**
 * Application Resource(CPUs, Mem)
 *
 * @author Shingo Omura
 */
final case class AppResource(cpus: Double, mem: Double) {
  // TODO support more resources
  import AppResource._
  def add[R <% AppResource](that: R) = AppResource(this.cpus + that.cpus, this.mem + that.mem)
  def inv = AppResource(-1 * this.cpus, -1 * this.mem)
  def sub[R <% AppResource](that: R) = this.add(that.inv)
  def lteq[R <% AppResource](that: R) = AppResourcePartialOrdering.lteq(this, that)
  def matches[R <% AppResource](that: R) = lteq(that)
}

object AppResource {

  // Offer can be viewed as AppResource
  implicit def Offer2AppResource(offer: Offer) = {
    val resourcesList = Option(offer.getResourcesList).map(_.asScala).getOrElse(List.empty)
    val cpuOffered = resourcesList.find(_.getName == TaskBuilder.cpusResourceName).map(_.getScalar.getValue).getOrElse(0.0d)
    val memOffered = resourcesList.find(_.getName == TaskBuilder.memResourceName).map(_.getScalar.getValue).getOrElse(0.0d)
    AppResource(cpuOffered, memOffered)
  }

  // AppDefinition can be viewed as AppResource
  implicit def AppDefinition2AppResource(app: AppDefinition)
  = if(app == null)
      AppResource.zero
     else
      AppResource(Option(app.cpus).getOrElse(0.0d),
                  Option(app.mem).getOrElse(0.0d))

  // for all R s.t. can be viewed as AppResource,
  // it supports explicit conversion method "asAppResource"
  implicit def toAsAppResource[R <% AppResource](r: R) = new {
    def asAppResource: AppResource = r
  }

  // AppResource forms partial ordering
  implicit val AppResourcePartialOrdering = new PartialOrdering[AppResource] {
    def tryCompare(x: AppResource, y: AppResource)
    = (x.cpus.compareTo(y.cpus), x.mem.compareTo(y.mem)) match {
      case (dc, dm) if dc == 0 && dm == 0 => Some(0)
      case (dc, dm) if dc <= 0 && dm <= 0 => Some(-1)
      case (dc, dm) if dc >= 0 && dm >= 0 => Some(1)
      case _ => None
    }
    def lteq(x: AppResource, y: AppResource) = tryCompare(x, y).exists(_ <= 0)
  }
  def zero = AppResource(0.0d, 0.0d)
}
