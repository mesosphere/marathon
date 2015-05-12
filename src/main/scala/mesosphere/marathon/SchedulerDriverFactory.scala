package mesosphere.marathon

import javax.inject.Inject

import mesosphere.chaos.http.HttpConf
import mesosphere.mesos.util.FrameworkIdUtil
import org.apache.mesos.SchedulerDriver

trait SchedulerDriverFactory {
  def createDriver(): SchedulerDriver
}

class MesosSchedulerDriverFactory @Inject() (
  holder: MarathonSchedulerDriverHolder,
  config: MarathonConf,
  httpConfig: HttpConf,
  frameworkIdUtil: FrameworkIdUtil,
  scheduler: MarathonScheduler)

    extends SchedulerDriverFactory {

  /**
    * As a side effect, the corresponding driver is set in the [[MarathonSchedulerDriverHolder]].
    */
  override def createDriver(): SchedulerDriver = {
    import mesosphere.util.ThreadPoolContext.context
    implicit val zkTimeout = config.zkFutureTimeout
    val frameworkId = frameworkIdUtil.fetch
    val driver = MarathonSchedulerDriver.newDriver(config, httpConfig, scheduler, frameworkId)
    holder.driver = Some(driver)
    driver
  }
}