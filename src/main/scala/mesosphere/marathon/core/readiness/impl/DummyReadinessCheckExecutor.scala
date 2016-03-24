package mesosphere.marathon.core.readiness.impl

import mesosphere.marathon.core.readiness.{ HttpResponse, ReadinessCheckResult, ReadinessCheckExecutor }
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor.ReadinessCheckSpec
import rx.lang.scala.Observable

/**
  * A stop-gap dummy implementation to allow testing.
  */
private[readiness] class DummyReadinessCheckExecutor extends ReadinessCheckExecutor {
  override def execute(readinessCheckInfo: ReadinessCheckSpec): Observable[ReadinessCheckResult] = {
    // dummy results
    def resultForIndex(idx: Int): ReadinessCheckResult = {
      val ready = idx >= 3

      ReadinessCheckResult(
        readinessCheckInfo.checkName,
        readinessCheckInfo.taskId,
        ready = ready,
        lastResponse = Some(
          HttpResponse(
            status = if (ready)
              readinessCheckInfo.httpStatusCodesForReady.head
            else
              (500 to 599).find(!readinessCheckInfo.httpStatusCodesForReady(_)).getOrElse(???),
            contentType = "application/json",
            body = "{}"
          )
        )
      )
    }

    Observable
      .interval(readinessCheckInfo.interval)
      .zipWithIndex.map { case (_, idx) => resultForIndex(idx) }
      .takeUntil(_.ready)
  }
}
