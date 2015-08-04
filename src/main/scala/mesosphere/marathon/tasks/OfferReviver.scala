package mesosphere.marathon.tasks

/**
  * Request offers from Mesos that we have already seen because we have new launching requirements.
  */
trait OfferReviver {
  def reviveOffers(): Unit
}
