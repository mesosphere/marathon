package mesosphere.marathon.core.jobs

import javax.inject.Inject

import mesosphere.marathon.state.AppDefinition

import scala.concurrent.Future

class JobSchedulerService @Inject() (jobScheduler: JobScheduler) {

  def scheduleJob(runSpec: AppDefinition): Future[Any] = jobScheduler.scheduleJob(runSpec)

}
