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
    * The offer processor while try to ensure timely execution.
    *
    * @param offer the offer to match
    * @return the future indicating when processing the offer has finished and if they were any errors
    */
  def processOffer(offer: Offer): Future[Unit]
}
