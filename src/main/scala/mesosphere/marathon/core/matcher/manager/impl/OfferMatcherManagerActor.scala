package mesosphere.marathon
package core.matcher.manager.impl

import akka.actor.{ Actor, Cancellable, Props }
import akka.event.LoggingReceive
import akka.pattern.pipe
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerConfig
import mesosphere.marathon.core.matcher.manager.impl.OfferMatcherManagerActor.{ CleanUpOverdueOffers, MatchOfferData, UnprocessedOffer }
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.metrics.{ Metrics, ServiceMetric, SettableGauge }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.tasks.ResourceUtil
import org.apache.mesos.Protos.{ Offer, OfferID }
import rx.lang.scala.Observer

import scala.collection.immutable.Queue
import scala.concurrent.Promise
import scala.util.Random
import scala.util.control.NonFatal

private[manager] class OfferMatcherManagerActorMetrics() {
  private[manager] val launchTokenGauge: SettableGauge =
    Metrics.atomicGauge(ServiceMetric, getClass, "launchTokens")
  private[manager] val currentOffersGauge: SettableGauge =
    Metrics.atomicGauge(ServiceMetric, getClass, "currentOffers")
}

/**
  * This actor offers one interface to a dynamic collection of matchers
  * and includes logic for limiting the amount of launches.
  */
private[manager] object OfferMatcherManagerActor {
  def props(
    metrics: OfferMatcherManagerActorMetrics,
    random: Random, clock: Clock,
    offerMatcherConfig: OfferMatcherManagerConfig, offersWanted: Observer[Boolean]): Props = {
    Props(new OfferMatcherManagerActor(metrics, random, clock, offerMatcherConfig, offersWanted))
  }

  /**
    * Internal data structure that bundles all related data if an offer is processed.
    *
    * @constructor Create a new instance that bundles offer and ops.
    * @param offer The offer that is matched.
    * @param promise promise for matched instance ops.
    * @param matcherQueue The offer matchers which should be applied to the offer.
    * @param ops All matched operations that should be applied to the offer.
    * @param matchPasses the number of cycles the offer has been passed to the list of matchers.
    * @param resendThisOffer true if there are matcher that want to see this offer again, otherwise false.
    */
  private[impl] case class MatchOfferData(
      offer: Offer,
      deadline: Timestamp,
      promise: Promise[OfferMatcher.MatchedInstanceOps],
      matcherQueue: Queue[OfferMatcher] = Queue.empty,
      ops: Seq[InstanceOpWithSource] = Seq.empty,
      matchPasses: Int = 0,
      resendThisOffer: Boolean = false) {

    def addMatcher(matcher: OfferMatcher): MatchOfferData = copy(matcherQueue = matcherQueue.enqueue(matcher))
    def nextMatcherOpt: Option[(OfferMatcher, MatchOfferData)] = {
      matcherQueue.dequeueOption map {
        case (nextMatcher, newQueue) => nextMatcher -> copy(matcherQueue = newQueue)
      }
    }

    def addInstances(added: Seq[InstanceOpWithSource]): MatchOfferData = {
      val leftOverOffer = added.foldLeft(offer) { (offer, nextOp) => nextOp.op.applyToOffer(offer) }

      copy(
        offer = leftOverOffer,
        ops = added ++ ops
      )
    }
  }

  /**
    * Internal data structure that holds the data for an offer that can not be processed immediately.
    */
  private[impl] case class UnprocessedOffer(offer: Offer, deadline: Timestamp, promise: Promise[OfferMatcher.MatchedInstanceOps]) {
    def isOverdue(clock: Clock): Boolean = clock.now() >= deadline
    def notOverdue(clock: Clock): Boolean = !isOverdue(clock)
  }

  /**
    * Recurrent timer tick to clean up overdue offers.
    */
  private case object CleanUpOverdueOffers
}

private[impl] class OfferMatcherManagerActor private (
  metrics: OfferMatcherManagerActorMetrics,
  random: Random, clock: Clock, conf: OfferMatcherManagerConfig, offersWantedObserver: Observer[Boolean])
    extends Actor with StrictLogging {

  var launchTokens: Int = 0

  var matchers: Set[OfferMatcher] = Set.empty

  var offerQueues: Map[OfferID, OfferMatcherManagerActor.MatchOfferData] = Map.empty

  var unprocessedOffers: List[UnprocessedOffer] = List.empty

  var timerTick: Option[Cancellable] = None

  override def preStart(): Unit = {
    implicit val ec = context.system.dispatcher
    val timeout = conf.offerMatchingTimeout()
    timerTick = Some(context.system.scheduler.schedule(timeout, timeout, self, CleanUpOverdueOffers))
  }

  override def postStop(): Unit = {
    timerTick.foreach(_.cancel())
  }

  override def receive: Receive = LoggingReceive {
    Seq[Receive](
      receiveSetLaunchTokens,
      receiveChangingMatchers,
      receiveProcessOffer,
      receiveMatchedInstances
    ).reduceLeft(_.orElse[Any, Unit](_))
  }

  def receiveSetLaunchTokens: Receive = {
    case OfferMatcherManagerDelegate.SetInstanceLaunchTokens(tokens) =>
      val tokensBeforeSet = launchTokens
      launchTokens = tokens
      metrics.launchTokenGauge.setValue(launchTokens.toLong)
      if (tokens > 0 && tokensBeforeSet <= 0)
        updateOffersWanted()
    case OfferMatcherManagerDelegate.AddInstanceLaunchTokens(tokens) =>
      launchTokens += tokens
      metrics.launchTokenGauge.setValue(launchTokens.toLong)
      if (tokens > 0 && launchTokens == tokens)
        updateOffersWanted()
  }

  def receiveChangingMatchers: Receive = {
    case OfferMatcherManagerDelegate.AddOrUpdateMatcher(matcher) =>
      if (!matchers(matcher)) {
        logger.info(s"activating matcher $matcher.")
        offerQueues.map { case (id, data) => id -> data.addMatcher(matcher) }
        matchers += matcher
        updateOffersWanted()
      }

      sender() ! OfferMatcherManagerDelegate.MatcherAdded(matcher)

    case OfferMatcherManagerDelegate.RemoveMatcher(matcher) =>
      if (matchers(matcher)) {
        logger.info(s"removing matcher $matcher")
        matchers -= matcher
        updateOffersWanted()
      }
      sender() ! OfferMatcherManagerDelegate.MatcherRemoved(matcher)
  }

  def offersWanted: Boolean = matchers.nonEmpty && launchTokens > 0
  def updateOffersWanted(): Unit = offersWantedObserver.onNext(offersWanted)

  def offerMatchers(offer: Offer): Queue[OfferMatcher] = {
    //the persistence id of a volume encodes the app id
    //we use this information as filter criteria
    val appReservations: Set[PathId] = offer.getResourcesList.view
      .filter(r => r.hasDisk && r.getDisk.hasPersistence && r.getDisk.getPersistence.hasId)
      .map(_.getDisk.getPersistence.getId)
      .collect { case LocalVolumeId(volumeId) => volumeId.runSpecId }
      .toSet
    val (reserved, normal) = matchers.toSeq.partition(_.precedenceFor.exists(appReservations))
    //1 give the offer to the matcher waiting for a reservation
    //2 give the offer to anybody else
    //3 randomize both lists to be fair
    (random.shuffle(reserved) ++ random.shuffle(normal)).to[Queue]
  }

  def receiveProcessOffer: Receive = {
    case ActorOfferMatcher.MatchOffer(offer: Offer, promise: Promise[OfferMatcher.MatchedInstanceOps]) if !offersWanted =>
      completeWithNoMatch("No offers wanted", offer, promise, resendThisOffer = matchers.nonEmpty)

    case ActorOfferMatcher.MatchOffer(offer: Offer, promise: Promise[OfferMatcher.MatchedInstanceOps]) =>
      val deadline = clock.now() + conf.offerMatchingTimeout()
      if (offerQueues.size < conf.maxParallelOffers()) {
        startProcessOffer(offer, deadline, promise)
      } else if (unprocessedOffers.size < conf.maxQueuedOffers()) {
        logger.debug(s"The maximum number of configured offers is processed at the moment. Queue offer ${offer.getId.getValue}.")
        unprocessedOffers ::= UnprocessedOffer(offer, deadline, promise)
      } else {
        completeWithNoMatch("Queue is full", offer, promise, resendThisOffer = true)
      }
    case CleanUpOverdueOffers =>
      logger.debug(
        s"Current State: LaunchTokens:$launchTokens OffersWanted:$offersWanted Matchers:${matchers.size} " +
          s"OfferQueues:${offerQueues.size} UnprocessedOffers:${unprocessedOffers.size}"
      )
      rejectElapsedOffers()
  }

  def receiveMatchedInstances: Receive = {
    case OfferMatcher.MatchedInstanceOps(offerId, addedOps, resendOffer) =>
      def processAddedInstances(data: MatchOfferData): MatchOfferData = {
        val dataWithInstances = try {
          val (acceptedOps, rejectedOps) =
            addedOps.splitAt(Seq(launchTokens, addedOps.size, conf.maxInstancesPerOffer() - data.ops.size).min)

          rejectedOps.foreach(_.reject("not enough launch tokens OR already scheduled sufficient instances on offer"))

          val newData: MatchOfferData = data.addInstances(acceptedOps)
          launchTokens -= acceptedOps.size
          metrics.launchTokenGauge.setValue(launchTokens.toLong)
          newData
        } catch {
          case NonFatal(e) =>
            logger.error(s"unexpected error processing ops for ${offerId.getValue} from ${sender()}", e)
            data
        }

        dataWithInstances.nextMatcherOpt match {
          case Some((matcher, contData)) =>
            val contDataWithActiveMatcher =
              if (addedOps.nonEmpty) contData.addMatcher(matcher)
              else contData
            offerQueues += offerId -> contDataWithActiveMatcher
            contDataWithActiveMatcher
          case None =>
            logger.warn(s"Got unexpected matched ops from ${sender()}: $addedOps")
            dataWithInstances
        }
      }

      offerQueues.get(offerId) match {
        case Some(data) =>
          val resend = data.resendThisOffer | resendOffer
          val nextData = processAddedInstances(data.copy(matchPasses = data.matchPasses + 1, resendThisOffer = resend))
          scheduleNextMatcherOrFinish(nextData)

        case None =>
          addedOps.foreach(_.reject(s"offer '${offerId.getValue}' already timed out"))
      }
  }

  /**
    * Filter all unprocessed offers that are overdue and decline.
    */
  def rejectElapsedOffers(): Unit = {
    // unprocessed offers are stacked in order with the newest element first: so we can use span here.
    val (valid, overdue) = unprocessedOffers.span(_.notOverdue(clock))
    logger.debug(s"Reject Elapsed offers. Unprocessed: ${unprocessedOffers.size} Overdue:${overdue.size}")
    unprocessedOffers = valid
    overdue.foreach { over =>
      completeWithNoMatch("Queue Timeout", over.offer, over.promise, resendThisOffer = true)
    }
    // safeguard: if matchers are stuck during offer matching, complete the match result
    offerQueues.valuesIterator.withFilter(_.deadline + conf.offerMatchingTimeout() < clock.now()).foreach { matcher =>
      logger.warn(s"Matcher did not respond with a matching result in time: ${matcher.offer.getId.getValue}")
      completeWithMatchResult(matcher, resendThisOffer = true)
    }
  }

  /**
    * If there are unprocessed offers, take the next one and start the process.
    */
  def startNextUnprocessedOffer(): Unit = {
    unprocessedOffers match {
      case head :: _ if head.isOverdue(clock) =>
        // Not cleaning the offer stack here, could lead to potentially very deep stack traces because:
        // startProcessOffer -> scheduleNextMatcherOrFinish -> startNextUnprocessedOffer
        rejectElapsedOffers()
        startNextUnprocessedOffer()
      case UnprocessedOffer(offer, _, promise) :: tail if offerQueues.size < conf.maxParallelOffers() =>
        logger.debug(s"Take unprocessed offer ${offer.getId.getValue}. Unprocessed offer count: ${unprocessedOffers.size}")
        unprocessedOffers = tail
        val deadline = clock.now() + conf.offerMatchingTimeout()
        startProcessOffer(offer, deadline, promise)
      case _ => //ignore
        logger.debug(s"Can not start next unprocessed offer. Unprocessed offer count: ${unprocessedOffers.size}")
    }
  }

  /**
    * Start processing an offer by asking all interested parties.
    */
  def startProcessOffer(
    offer: Offer,
    deadline: Timestamp,
    promise: Promise[OfferMatcher.MatchedInstanceOps]): Unit = {
    logger.info(s"Start processing offer ${offer.getId.getValue}. Current offer matcher count: ${offerQueues.size}")

    // setup initial offer data
    val randomizedMatchers = offerMatchers(offer)
    val data = OfferMatcherManagerActor.MatchOfferData(offer, deadline, promise, randomizedMatchers)
    offerQueues += offer.getId -> data
    metrics.currentOffersGauge.setValue(offerQueues.size.toLong)

    // process offer for the first time
    scheduleNextMatcherOrFinish(data)
  }

  /**
    * Send the offer to the next matcher in the queue if possible.
    * If there is no matcher left or no launch token left, complete the process.
    */
  def scheduleNextMatcherOrFinish(data: MatchOfferData): Unit = {
    val nextMatcherOpt = if (data.deadline < clock.now()) {
      logger.info(s"Deadline for ${data.offer.getId.getValue} overdue. Scheduled ${data.ops.size} ops so far.")
      None
    } else if (data.ops.size >= conf.maxInstancesPerOffer()) {
      logger.info(
        s"Already scheduled the maximum number of ${data.ops.size} instances on this offer. " +
          s"Increase with --${conf.maxInstancesPerOffer.name}.")
      None
    } else if (launchTokens <= 0) {
      logger.info(
        s"No launch tokens left for ${data.offer.getId.getValue}. " +
          "Tune with --launch_tokens/launch_token_refresh_interval.")
      None
    } else {
      data.nextMatcherOpt
    }

    nextMatcherOpt match {
      case Some((nextMatcher, newData)) =>
        import context.dispatcher
        logger.debug(s"Query next offer matcher $nextMatcher for offer id ${data.offer.getId.getValue}")
        nextMatcher
          .matchOffer(newData.offer)
          .recover {
            case NonFatal(e) =>
              logger.warn("Received error from", e)
              MatchedInstanceOps.noMatch(data.offer.getId, resendThisOffer = true)
          }.pipeTo(self)
      case None =>
        completeWithMatchResult(data, data.resendThisOffer)
        // one offer match is finished. Let's see if we can start an unprocessed offer now.
        if (offersWanted) startNextUnprocessedOffer()
    }
  }

  def completeWithMatchResult(data: MatchOfferData, resendThisOffer: Boolean): Unit = {
    data.promise.trySuccess(OfferMatcher.MatchedInstanceOps(data.offer.getId, data.ops, resendThisOffer))
    offerQueues -= data.offer.getId
    metrics.currentOffersGauge.setValue(offerQueues.size.toLong)
    logger.info(s"Finished processing ${data.offer.getId.getValue} from ${data.offer.getHostname}. " +
      s"Matched ${data.ops.size} ops after ${data.matchPasses} passes. " +
      s"First 10: ${ResourceUtil.displayResources(data.offer.getResourcesList.to[Seq], 10)}")
    logger.debug(s"First 1000 offers: ${ResourceUtil.displayResources(data.offer.getResourcesList.to[Seq], 1000)}.")
  }

  def completeWithNoMatch(reason: String, offer: Offer, promise: Promise[MatchedInstanceOps], resendThisOffer: Boolean): Unit = {
    promise.trySuccess(MatchedInstanceOps.noMatch(offer.getId, resendThisOffer))
    logger.info(s"No match for:${offer.getId.getValue} from:${offer.getHostname} reason:$reason")
  }
}

