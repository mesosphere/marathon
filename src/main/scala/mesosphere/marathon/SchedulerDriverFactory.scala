package mesosphere.marathon

import javax.inject.Inject

import mesosphere.chaos.http.HttpConf
import mesosphere.util.state.FrameworkIdUtil
import org.apache.mesos.{ Scheduler, SchedulerDriver }
import org.slf4j.LoggerFactory

trait SchedulerDriverFactory {
  def createDriver(): SchedulerDriver
}

class MesosSchedulerDriverFactory @Inject() (
  holder: MarathonSchedulerDriverHolder,
  config: MarathonConf,
  httpConfig: HttpConf,
  frameworkIdUtil: FrameworkIdUtil,
  scheduler: Scheduler)

    extends SchedulerDriverFactory {

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  log.debug("using scheduler " + scheduler)

  /**
    * As a side effect, the corresponding driver is set in the [[MarathonSchedulerDriverHolder]].
    */
  override def createDriver(): SchedulerDriver = {
    implicit val zkTimeout = config.zkTimeoutDuration
    val frameworkId = frameworkIdUtil.fetch()
    val driver = MarathonSchedulerDriver.newDriver(config, httpConfig, scheduler, frameworkId)
    holder.driver = Some(driver)
    driver
  }
}
