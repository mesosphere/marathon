package mesosphere.marathon

import com.typesafe.scalalogging.StrictLogging
import javax.inject.Inject

import mesosphere.marathon.storage.repository.FrameworkIdRepository
import org.apache.mesos.{Scheduler, SchedulerDriver}

import scala.concurrent.Await

trait SchedulerDriverFactory {
  def createDriver(): SchedulerDriver
}

class MesosSchedulerDriverFactory @Inject() (
    holder: MarathonSchedulerDriverHolder,
    config: MarathonConf,
    httpConfig: HttpConf,
    frameworkIdRepository: FrameworkIdRepository,
    scheduler: Scheduler)

  extends SchedulerDriverFactory with StrictLogging {

  logger.debug("using scheduler " + scheduler)

  /**
    * As a side effect, the corresponding driver is set in the [[MarathonSchedulerDriverHolder]].
    */
  override def createDriver(): SchedulerDriver = {
    implicit val zkTimeout = config.zkTimeoutDuration
    val frameworkId = Await.result(frameworkIdRepository.get(), zkTimeout).map(_.toProto)
    val driver = MarathonSchedulerDriver.newDriver(config, httpConfig, scheduler, frameworkId)
    holder.driver = Some(driver)
    driver
  }
}
