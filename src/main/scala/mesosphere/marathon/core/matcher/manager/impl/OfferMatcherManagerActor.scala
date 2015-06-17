package mesosphere.marathon.core.matcher.manager.impl

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.event.LoggingReceive
import akka.pattern.pipe
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.matcher.base.OfferMatcher
import OfferMatcher.{ MatchedTasks, TaskWithSource }
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.manager.{ OfferMatcherManagerConfig }
import mesosphere.marathon.core.matcher.manager.impl.OfferMatcherManagerActor.OfferData
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.tasks.ResourceUtil
import org.apache.mesos.Protos.{ Offer, OfferID, Resource }
import org.slf4j.LoggerFactory
import rx.lang.scala.Observer

import scala.collection.JavaConverters._
import scala.collection.immutable.Queue
import scala.util.Random
import scala.util.control.NonFatal

/**
  * This actor offers one interface to a dynamic collection of matchers
  * and includes logic for limiting the amount of launches.
  */
private[manager] object OfferMatcherManagerActor {
  def props(
    random: Random, clock: Clock,
    offerMatcherConfig: OfferMatcherManagerConfig, offersWanted: Observer[Boolean]): Props = {
    Props(new OfferMatcherManagerActor(random, clock, offerMatcherConfig, offersWanted))
  }

  private val log = LoggerFactory.getLogger(getClass)
  private case class OfferData(
      offer: Offer,
      deadline: Timestamp,
      sender: ActorRef,
      matcherQueue: Queue[OfferMatcher],
      tasks: Seq[TaskWithSource],
      matchPasses: Int = 0) {

    def addMatcher(matcher: OfferMatcher): OfferData = copy(matcherQueue = matcherQueue.enqueue(matcher))
    def nextMatcherOpt: Option[(OfferMatcher, OfferData)] = {
      matcherQueue.dequeueOption map {
        case (nextMatcher, newQueue) => nextMatcher -> copy(matcherQueue = newQueue)
      }
    }

    def addTasks(added: Seq[TaskWithSource]): OfferData = {
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
}

private class OfferMatcherManagerActor private (
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
      if (tokens > 0 && tokensBeforeSet <= 0)
        updateOffersWanted()
    case OfferMatcherManagerDelegate.AddTaskLaunchTokens(tokens) =>
      launchTokens += tokens
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
      sender() ! OfferMatcher.MatchedTasks(offer.getId, Seq.empty)

    case ActorOfferMatcher.MatchOffer(deadline, offer: Offer) =>
      log.debug(s"Start processing offer ${offer.getId.getValue}")

      // setup initial offer data
      val randomizedMatchers = random.shuffle(matchers).to[Queue]
      val data = OfferMatcherManagerActor.OfferData(offer, deadline, sender(), randomizedMatchers, Seq.empty)
      offerQueues += offer.getId -> data

      // deal with the timeout
      import context.dispatcher
      context.system.scheduler.scheduleOnce(
        clock.now().until(deadline),
        self,
        OfferMatcher.MatchedTasks(offer.getId, Seq.empty))

      // process offer for the first time
      scheduleNextMatcherOrFinish(data)
  }

  private[this] def receiveMatchedTasks: Receive = {
    case OfferMatcher.MatchedTasks(offerId, addedTasks) =>
      def processAddedTasks(data: OfferData): OfferData = {
        val dataWithTasks = try {
          val (launchTasks, rejectedTasks) =
            addedTasks.splitAt(Seq(launchTokens, addedTasks.size, conf.maxTasksPerOffer() - data.tasks.size).min)

          rejectedTasks.foreach(_.reject("not enough launch tokens OR already scheduled sufficient tasks on offer"))

          val newData: OfferData = data.addTasks(launchTasks)
          launchTokens -= launchTasks.size
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
          val nextData = processAddedTasks(data.copy(matchPasses = data.matchPasses + 1))
          scheduleNextMatcherOrFinish(nextData)

        case None =>
          addedTasks.foreach(_.reject(s"offer '${offerId.getValue}' already timed out"))
      }
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
              MatchedTasks(data.offer.getId, Seq.empty)
          }.pipeTo(self)
      case None =>
        data.sender ! OfferMatcher.MatchedTasks(data.offer.getId, data.tasks)
        offerQueues -= data.offer.getId
        log.info(s"Finished processing ${data.offer.getId.getValue}. " +
          s"Matched ${data.tasks.size} tasks after ${data.matchPasses} passes. " +
          s"${ResourceUtil.displayResources(data.offer.getResourcesList.asScala)} left.")
    }
  }
}

