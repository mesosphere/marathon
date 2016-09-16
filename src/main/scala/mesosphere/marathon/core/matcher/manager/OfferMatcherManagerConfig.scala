package mesosphere.marathon.core.matcher.manager

import org.rogach.scallop.ScallopConf

trait OfferMatcherManagerConfig extends ScallopConf {
  lazy val maxInstancesPerOfferFlag = opt[Int](
    "max_instances_per_offer",
    descr = "Max instances per offer. Do not start more than this number of app or pod instances on a single offer.")

  /**
    * WARNING! nothing outside of this file should reference this field.
    * Since I can't turn off deprecation errors for individual fields I'm leaving this unmarked for deprecation via
    * annotation. But this is definitely a deprecated field that will be removed in an upcoming release.
    *
    * TODO(jdef) remove this in the 1.5 release cycle
    */
  lazy val maxTasksPerOfferFlag = opt[Int](
    "max_tasks_per_offer",
    descr = "(deprecated) Maximum tasks per offer. Do not start more than this number of tasks on a single offer.",
    default = Some(5))

  // TODO(jdef) simplify this once maxTasksPerOfferFlag has been removed
  def maxInstancesPerOffer(): Int = maxInstancesPerOfferFlag.get.getOrElse(maxTasksPerOfferFlag.apply())
}
