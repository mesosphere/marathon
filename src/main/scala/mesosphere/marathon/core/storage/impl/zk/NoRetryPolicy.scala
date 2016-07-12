package mesosphere.marathon.core.storage.impl.zk

import org.apache.curator.{ RetryPolicy, RetrySleeper }

/**
  * Inform Curator not to attempt a Retry, instead, we use [[mesosphere.marathon.util.Retry]]
  */
object NoRetryPolicy extends RetryPolicy {
  override def allowRetry(retryCount: Int, elapsedTimeMs: Long, sleeper: RetrySleeper): Boolean = false
}
