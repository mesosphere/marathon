package mesosphere.marathon.core.matcher.manager.impl

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.event.LoggingReceive
import akka.pattern.pipe
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ MatchedTaskOps, TaskOpWithSource }
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerConfig
import mesosphere.marathon.core.matcher.manager.impl.OfferMatcherManagerActor.{ MatchTimeout, OfferData }
import mesosphere.marathon.metrics.Metrics.AtomicIntGauge
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.tasks.ResourceUtil
import org.apache.mesos.Protos.{ Offer, OfferID, Resource }
import org.slf4j.LoggerFactory
import rx.lang.scala.Observer

import scala.collection.JavaConverters._
import scala.collection.immutable.Queue
import scala.util.Random
import scala.util.control.NonFatal

private[manager] class OfferMatcherManagerActorMetrics(metrics: Metrics) {
  private[manager] val launchTokenGauge: AtomicIntGauge =
    metrics.gauge(metrics.name(MetricPrefixes.SERVICE, getClass, "launchTokens"), new AtomicIntGauge)
  private[manager] val currentOffersGauge: AtomicIntGauge =
    metrics.gauge(metrics.name(MetricPrefixes.SERVICE, getClass, "currentOffers"), new AtomicIntGauge)
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

  private val log = LoggerFactory.getLogger(getClass)
  private case class OfferData(
      offer: Offer,
      deadline: Timestamp,
      sender: ActorRef,
      matcherQueue: Queue[OfferMatcher],
      tasks: Seq[TaskOpWithSource],
      matchPasses: Int = 0,
      resendThisOffer: Boolean = false) {

    def addMatcher(matcher: OfferMatcher): OfferData = copy(matcherQueue = matcherQueue.enqueue(matcher))
    def nextMatcherOpt: Option[(OfferMatcher, OfferData)] = {
      matcherQueue.dequeueOption map {
        case (nextMatcher, newQueue) => nextMatcher -> copy(matcherQueue = newQueue)
      }
    }

    def addTasks(added: Seq[TaskOpWithSource]): OfferData = {
      val offerResources: Seq[Resource] = offer.getResourcesList.asScala
      val taskResources: Seq[Resource] = added.map(_.taskInfo).flatMap(_.getResourcesList.asScala)
      val leftOverResources = ResourceUtil.consumeResources(offerResources, taskResources)
      val leftOverOffer = offer.toBuilder.clearResources().addAllResources(leftOverResources.asJava).build()
      copy(
        offer = leftOverOffer,
        tasks = added ++ tasks
      )
    }
  }

  private case class MatchTimeout(offerId: OfferID)
}

private class OfferMatcherManagerActor private (
  metrics: OfferMatcherManagerActorMetrics,
  random: Random, clock: Clock, conf: OfferMatcherManagerConfig, offersWantedObserver: Observer[Boolean])
    extends Actor with ActorLogging {

  private[this] var launchTokens: Int = 0

  private[this] var matchers: Set[OfferMatcher] = Set.empty

  private[this] var offerQueues: Map[OfferID, OfferMatcherManagerActor.OfferData] = Map.empty

  override def receive: Receive = LoggingReceive {
    Seq[Receive](
      receiveSetLaunchTokens,
      receiveChangingMatchers,
      receiveProcessOffer,
      receiveMatchedTasks
    ).reduceLeft(_.orElse[Any, Unit](_))
  }

  private[this] def receiveSetLaunchTokens: Receive = {
    case OfferMatcherManagerDelegate.SetTaskLaunchTokens(tokens) =>
      val tokensBeforeSet = launchTokens
      launchTokens = tokens
      metrics.launchTokenGauge.setValue(launchTokens)
      if (tokens > 0 && tokensBeforeSet <= 0)
        updateOffersWanted()
    case OfferMatcherManagerDelegate.AddTaskLaunchTokens(tokens) =>
      launchTokens += tokens
      metrics.launchTokenGauge.setValue(launchTokens)
      if (tokens > 0 && launchTokens == tokens)
        updateOffersWanted()
  }

  private[this] def receiveChangingMatchers: Receive = {
    case OfferMatcherManagerDelegate.AddOrUpdateMatcher(matcher) =>
      if (!matchers(matcher)) {
        log.info("activating matcher {}.", matcher)
        offerQueues.mapValues(_.addMatcher(matcher))
        matchers += matcher
        updateOffersWanted()
      }

      sender() ! OfferMatcherManagerDelegate.MatcherAdded(matcher)

    case OfferMatcherManagerDelegate.RemoveMatcher(matcher) =>
      if (matchers(matcher)) {
        log.info("removing matcher {}", matcher)
        matchers -= matcher
        updateOffersWanted()
      }
      sender() ! OfferMatcherManagerDelegate.MatcherRemoved(matcher)
  }

  private[this] def offersWanted: Boolean = matchers.nonEmpty && launchTokens > 0
  private[this] def updateOffersWanted(): Unit = offersWantedObserver.onNext(offersWanted)

  private[this] def receiveProcessOffer: Receive = {
    case ActorOfferMatcher.MatchOffer(deadline, offer: Offer) if !offersWanted =>
      log.debug(s"Ignoring offer ${offer.getId.getValue}: No one interested.")
      sender() ! OfferMatcher.MatchedTaskOps(offer.getId, Seq.empty, resendThisOffer = false)

    case ActorOfferMatcher.MatchOffer(deadline, offer: Offer) =>
      log.debug(s"Start processing offer ${offer.getId.getValue}")

      // setup initial offer data
      val randomizedMatchers = random.shuffle(matchers).to[Queue]
      val data = OfferMatcherManagerActor.OfferData(offer, deadline, sender(), randomizedMatchers, Seq.empty)
      offerQueues += offer.getId -> data
      metrics.currentOffersGauge.setValue(offerQueues.size)

      // deal with the timeout
      import context.dispatcher
      context.system.scheduler.scheduleOnce(
        clock.now().until(deadline),
        self,
        MatchTimeout(offer.getId))

      // process offer for the first time
      scheduleNextMatcherOrFinish(data)
  }

  private[this] def receiveMatchedTasks: Receive = {
    case OfferMatcher.MatchedTaskOps(offerId, addedTasks, resendOffer) =>
      def processAddedTasks(data: OfferData): OfferData = {
        val dataWithTasks = try {
          val (launchTasks, rejectedTasks) =
            addedTasks.splitAt(Seq(launchTokens, addedTasks.size, conf.maxTasksPerOffer() - data.tasks.size).min)

          rejectedTasks.foreach(_.reject("not enough launch tokens OR already scheduled sufficient tasks on offer"))

          val newData: OfferData = data.addTasks(launchTasks)
          launchTokens -= launchTasks.size
          metrics.launchTokenGauge.setValue(launchTokens)
          newData
        }
        catch {
          case NonFatal(e) =>
            log.error(s"unexpected error processing tasks for ${offerId.getValue} from ${sender()}", e)
            data
        }

        dataWithTasks.nextMatcherOpt match {
          case Some((matcher, contData)) =>
            val contDataWithActiveMatcher =
              if (addedTasks.nonEmpty) contData.addMatcher(matcher)
              else contData
            offerQueues += offerId -> contDataWithActiveMatcher
            contDataWithActiveMatcher
          case None =>
            log.warning("Got unexpected matched tasks.")
            dataWithTasks
        }
      }

      offerQueues.get(offerId) match {
        case Some(data) =>
          val resend = data.resendThisOffer | resendOffer
          val nextData = processAddedTasks(data.copy(matchPasses = data.matchPasses + 1, resendThisOffer = resend))
          scheduleNextMatcherOrFinish(nextData)

        case None =>
          addedTasks.foreach(_.reject(s"offer '${offerId.getValue}' already timed out"))
      }

    case MatchTimeout(offerId) =>
      // When the timeout is reached, we will answer with all matching tasks we found until then.
      // Since we cannot be sure if we found all matching tasks, we set resendThisOffer to true.
      offerQueues.get(offerId).foreach(sendMatchResult(_, resendThisOffer = true))
  }

  private[this] def scheduleNextMatcherOrFinish(data: OfferData): Unit = {
    val nextMatcherOpt = if (data.deadline < clock.now()) {
      log.warning(s"Deadline for ${data.offer.getId.getValue} overdue. Scheduled ${data.tasks.size} tasks so far.")
      None
    }
    else if (data.tasks.size >= conf.maxTasksPerOffer()) {
      log.info(
        s"Already scheduled the maximum number of ${data.tasks.size} tasks on this offer. " +
          s"Increase with --${conf.maxTasksPerOffer.name}.")
      None
    }
    else if (launchTokens <= 0) {
      log.info(
        s"No launch tokens left for ${data.offer.getId.getValue}. " +
          s"Tune with --launch_tokens/launch_token_refresh_interval.")
      None
    }
    else {
      data.nextMatcherOpt
    }

    nextMatcherOpt match {
      case Some((nextMatcher, newData)) =>
        import context.dispatcher
        log.debug(s"query next offer matcher {} for offer id {}", nextMatcher, data.offer.getId.getValue)
        nextMatcher
          .matchOffer(newData.deadline, newData.offer)
          .recover {
            case NonFatal(e) =>
              log.warning("Received error from {}", e)
              MatchedTaskOps(data.offer.getId, Seq.empty, resendThisOffer = true)
          }.pipeTo(self)
      case None => sendMatchResult(data, data.resendThisOffer)
    }
  }

  private[this] def sendMatchResult(data: OfferData, resendThisOffer: Boolean): Unit = {
    data.sender ! OfferMatcher.MatchedTaskOps(data.offer.getId, data.tasks, resendThisOffer)
    offerQueues -= data.offer.getId
    metrics.currentOffersGauge.setValue(offerQueues.size)
    //scalastyle:off magic.number
    val maxRanges = if (log.isDebugEnabled) 1000 else 10
    //scalastyle:on magic.number
    log.info(s"Finished processing ${data.offer.getId.getValue}. " +
      s"Matched ${data.tasks.size} tasks after ${data.matchPasses} passes. " +
      s"${ResourceUtil.displayResources(data.offer.getResourcesList.asScala, maxRanges)} left.")
  }
}

