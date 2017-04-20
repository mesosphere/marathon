package mesosphere.marathon
package core.matcher.base.util

import org.apache.mesos.{ Protos => Mesos }
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps

import scala.collection.immutable

/**
  * Helper for tests that want to validate offer matching operations.
  */
trait OfferMatcherSpec {

  /** All TaskInfos of launched tasks. */
  def launchedTaskInfos(matched: MatchedInstanceOps): immutable.Seq[Mesos.TaskInfo] = matched.ops.collect {
    case InstanceOp.LaunchTask(taskInfo, _, _, _) => taskInfo
  }(collection.breakOut)
}
