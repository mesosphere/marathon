package mesosphere.marathon

import org.apache.mesos.Protos.{TaskID, FrameworkInfo}
import org.apache.mesos.MesosSchedulerDriver
import java.util.logging.Logger
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.AppUpdate
import mesosphere.marathon.state.{AppRepository, Timestamp}
import com.google.common.util.concurrent.AbstractExecutionThreadService
import javax.inject.{Named, Inject}
import java.util.{TimerTask, Timer}
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.{Duration, MILLISECONDS}
import java.util.concurrent.atomic.AtomicBoolean
import com.twitter.common.base.ExceptionalCommand
import com.twitter.common.zookeeper.Group.JoinException
import scala.Option
import com.twitter.common.zookeeper.Candidate
import com.twitter.common.zookeeper.Candidate.Leader
import scala.util.Random
import mesosphere.mesos.util.FrameworkIdUtil
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.health.HealthCheckManager

/**
 * Wrapper class for the scheduler
 *
 * @author Tobi Knaup
 */
class MarathonSchedulerDriver @Inject()(config: MarathonConf,
                                        scheduler: MarathonScheduler,
                                        frameworkIdUtil: FrameworkIdUtil) {

  val log = Logger.getLogger(getClass.getName)

  val frameworkName = "marathon-" + Main.properties.getProperty("marathon.version")

  val frameworkInfo = FrameworkInfo.newBuilder()
    .setName(frameworkName)
    .setFailoverTimeout(config.mesosFailoverTimeout())
    .setUser(config.mesosUser())
    .setCheckpoint(config.checkpoint())

  // Set the framework ID
  frameworkIdUtil.fetch() match {
    case Some(id) => {
      log.info(s"Setting framework ID to ${id.getValue}")
      frameworkInfo.setId(id)
    }
    case None => {
      log.info("No previous framework ID found")
    }
  }
  // Set the role, if provided.
  config.mesosRole.get.map(frameworkInfo.setRole)

  val driver = new MesosSchedulerDriver(
    scheduler,
    frameworkInfo.build,
    config.mesosMaster()
  )

}
