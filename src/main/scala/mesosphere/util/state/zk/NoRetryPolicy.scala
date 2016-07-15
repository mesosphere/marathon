package mesosphere.util.state.zk

import org.apache.curator.{ RetryPolicy, RetrySleeper }

object NoRetryPolicy extends RetryPolicy {
  override def allowRetry(retryCount: Int, elapsedTimeMs: Long, sleeper: RetrySleeper): Boolean = false
}
