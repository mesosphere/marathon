package mesosphere.marathon.core.matcher.base.util

import org.apache.mesos.Protos
import org.apache.mesos.Protos.Offer

/**
  * Helper methods for creating operations on offers.
  */
private[base] object OfferOperation {

  /** Create a launch operation for the given taskInfo. */
  def launch(taskInfo: Protos.TaskInfo): Offer.Operation = {
    val launch = Offer.Operation.Launch.newBuilder()
      .addTaskInfos(taskInfo)
      .build()

    Offer.Operation.newBuilder()
      .setType(Protos.Offer.Operation.Type.LAUNCH)
      .setLaunch(launch)
      .build()
  }
}
