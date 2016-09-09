package mesosphere.marathon.core.pod

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.state.Timestamp

import scala.collection.immutable.Seq

trait PodInstance extends Instance {

  def state: InstanceState = ???
  def isLaunched: Boolean = ???

  def isReserved: Boolean = ???
  def isCreated: Boolean = ???
  def isError: Boolean = ???
  def isFailed: Boolean = ???
  def isFinished: Boolean = ???
  def isKilled: Boolean = ???
  def isKilling: Boolean = ???
  def isRunning: Boolean = ???
  def isStaging: Boolean = ???
  def isStarting: Boolean = ???
  def isUnreachable: Boolean = ???
  def isGone: Boolean = ???
  def isUnknown: Boolean = ???
  def isDropped: Boolean = ???
}

object PodInstance {

  case class LaunchedEphemeral(
    id: Instance.Id,
    agentInfo: Instance.AgentInfo,
    runSpecVersion: Timestamp,
    status: Any, // TODO(jdef) pods instances need status
    hostPorts: Seq[Int]) extends PodInstance
}