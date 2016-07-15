package mesosphere.marathon.core.storage

import akka.actor.Scheduler
import akka.stream.Materializer
import mesosphere.marathon.AllConf
import mesosphere.marathon.core.storage.repository.AppRepository
import mesosphere.marathon.metrics.Metrics

import scala.concurrent.ExecutionContext

trait StorageModule {
  def appRepository: AppRepository
}

object StorageModule {
  def apply(conf: AllConf, metrics: Metrics, mat: Materializer, ctx: ExecutionContext,
            scheduler: Scheduler): StorageModule = {

  }
}

private[storage] class StorageModuleImpl extends StorageModule {

}