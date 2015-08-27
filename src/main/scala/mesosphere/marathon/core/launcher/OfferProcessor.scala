package mesosphere.marathon.core.launcher

import org.apache.mesos.Protos.Offer

import scala.concurrent.Future

/**
  * Process offer by matching tasks and launching them when appropriate.
  */
trait OfferProcessor {
  /**
    * Process offer by matching tasks and launching them when appropriate.
    *
    * The offer processor will try to ensure a timely execution.
    *
    * @param offer the offer to match
    * @return the future indicating when the processing of the offer has finished and if there were any errors
    */
  def processOffer(offer: Offer): Future[Unit]
}
