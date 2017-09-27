package mesosphere.marathon
package core.election

import akka.NotUsed
import akka.actor.{ ActorSystem, Cancellable }
import akka.stream.ClosedShape
import akka.stream.scaladsl.{ Broadcast, GraphDSL, RunnableGraph, Flow, Sink, Source }
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import kamon.metric.instrument.Time
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.base.CrashStrategy
import mesosphere.marathon.stream.Repeater
import scala.util.{ Failure, Success }

/**
  * ElectionService is implemented by leadership election mechanisms.
  *
  * This trait is used in conjunction with [[ElectionCandidate]]. From their point of view,
  * a leader election works as follow:
  *
  * -> ElectionService.offerLeadership(candidate)     |      - A leader election is triggered.
  *                                                          — Once `candidate` is elected as a leader,
  *                                                            its `startLeadership` is called.
  *
  * Please note that upon a call to [[ElectionService.abdicateLeadership]], or
  * any error in any of method of [[ElectionService]], or a leadership loss,
  * [[ElectionCandidate.stopLeadership]] is called if [[ElectionCandidate.startLeadership]]
  * has been called before, and JVM gets shutdown.
  *
  * It effectively means that a particular instance of Marathon can be elected at most once during its lifetime.
  */
trait ElectionService {
  /**
    * isLeader checks whether this instance is the leader
    *
    * @return true if this instance is the leader
    */
  def isLeader: Boolean

  /**
    * localHostPort return a host:port pair of this running instance that is used for discovery.
    *
    * @return host:port of this instance.
    */
  def localHostPort: String

  /**
    * leaderHostPort return a host:port pair of the leader, if it is elected.
    *
    * @return Some(host:port) of the leader, or None if no leader exists or is known
    */
  def leaderHostPort: Option[String]

  /**
    * abdicateLeadership is called to resign from leadership.
    */
  def abdicateLeadership(): Unit

  def leaderStateEvents: Source[LeadershipState, Cancellable]
  def leaderTransitionEvents: Source[LeadershipTransition, Cancellable]
}

class ElectionServiceImpl(
    hostPort: String,
    leaderEventsSource: Source[LeadershipState, Cancellable],
    crashStrategy: CrashStrategy)(implicit system: ActorSystem) extends ElectionService with StrictLogging {

  import ElectionService._
  @volatile private[this] var lastState: LeadershipState = LeadershipState.Standby(None)
  implicit private lazy val materializer = ActorMaterializer()

  override def isLeader: Boolean =
    lastState == LeadershipState.ElectedAsLeader

  override def localHostPort: String = hostPort

  override def leaderHostPort: Option[String] = lastState match {
    case LeadershipState.ElectedAsLeader =>
      Some(hostPort)
    case LeadershipState.Standby(currentLeader) =>
      currentLeader
  }

  override def abdicateLeadership(): Unit = {
    leaderSubscription.cancel()
  }

  private def initializeStream() = {
    val localEventListenerSink = Sink.foreach[LeadershipState] { event =>
      lastState = event
    }
    val crashOnLostLeaderSink = Sink.foreach[LeadershipTransition] {
      case LeadershipTransition.LostLeadership =>
        logger.error(s"Lost leadership; crashing")
        crashStrategy.crash()
      case _ =>
        ()
    }

    val leadershipTransitionsBroadcast = Repeater.sink[LeadershipTransition](32, OverflowStrategy.fail)(system.dispatcher)
    val leaderEventsBroadcastSink = Repeater.sink[LeadershipState](32, OverflowStrategy.fail)(system.dispatcher)

    val (leaderStream, leaderStreamDone, leaderTransitionEvents, leaderStateEvents) =
      RunnableGraph.fromGraph(GraphDSL.create(
        leaderEventsSource, localEventListenerSink, leadershipTransitionsBroadcast, leaderEventsBroadcastSink)(
        (_, _, _, _)) { implicit b =>
          { (leaderEventsSource, localEventListenerSink, leadershipTransitionsBroadcast, leaderEventsBroadcastSink) =>
            import GraphDSL.Implicits._
            // We defensively specify eagerCancel as true; if any of the components in the stream close or fail, then
            // we'll help to make it obvious by closing the entire graph (and, by consequence, crashing).
            // Akka will log all stream failures, by default.
            val stateBroadcast = b.add(Broadcast[LeadershipState](3, eagerCancel = true))
            val transitionBroadcast = b.add(Broadcast[LeadershipTransition](3, eagerCancel = true))
            leaderEventsSource ~> stateBroadcast.in
            stateBroadcast ~> localEventListenerSink
            stateBroadcast ~> leaderEventsBroadcastSink
            stateBroadcast ~> leaderTransitionFlow ~> transitionBroadcast.in

            transitionBroadcast ~> leadershipTransitionsBroadcast
            transitionBroadcast ~> metricsSink
            transitionBroadcast ~> crashOnLostLeaderSink
            ClosedShape
          }
        }).run

    // When the leadership stream terminates, for any reason, we suicide
    leaderStreamDone.onComplete {
      case Failure(ex) =>
        logger.info(s"Leadership ended with failure; exiting", ex)
        crashStrategy.crash()
      case Success(_) =>
        logger.info(s"Leadership ended gracefully; exiting")
        crashStrategy.crash()
    }(ExecutionContexts.callerThread)

    (leaderStream, leaderTransitionEvents, leaderStateEvents)
  }

  val (leaderSubscription, leaderTransitionEvents, leaderStateEvents) = initializeStream()
}

object ElectionService extends StrictLogging {
  private val leaderDurationMetric = "service.mesosphere.marathon.leaderDuration"

  val metricsSink = Sink.foreach[LeadershipTransition] {
    case LeadershipTransition.ObtainedLeadership =>
      val startedAt = System.currentTimeMillis()
      Kamon.metrics.gauge(leaderDurationMetric, Time.Milliseconds)(System.currentTimeMillis() - startedAt)
    case LeadershipTransition.LostLeadership =>
      Kamon.metrics.removeGauge(leaderDurationMetric)
  }

  private[election] def leaderTransitionFlow: Flow[LeadershipState, LeadershipTransition, NotUsed] =
    Flow[LeadershipState].statefulMapConcat { () =>
      var haveLeadership = false

      {
        case LeadershipState.ElectedAsLeader if !haveLeadership =>
          haveLeadership = true
          List(LeadershipTransition.ObtainedLeadership)
        case LeadershipState.Standby(_) if haveLeadership =>
          haveLeadership = false
          List(LeadershipTransition.LostLeadership)
        case _ =>
          Nil
      }
    }
}

/** Local leadership events. They are not delivered via the event endpoints. */
sealed trait LeadershipState

object LeadershipState {
  case object ElectedAsLeader extends LeadershipState
  case class Standby(currentLeader: Option[String]) extends LeadershipState
}

sealed trait LeadershipTransition
object LeadershipTransition {
  case object LostLeadership extends LeadershipTransition
  case object ObtainedLeadership extends LeadershipTransition
}
