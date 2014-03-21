package mesosphere.marathon

import org.rogach.scallop.ScallopConf
import java.net.InetSocketAddress
import scala.sys.SystemProperties

/**
 * @author Tobi Knaup
 */

trait MarathonConf extends ScallopConf {

  lazy val mesosMaster = opt[String]("master",
    descr = "The URL of the Mesos master",
    required = true,
    noshort = true)

  lazy val mesosFailoverTimeout = opt[Long]("failover_timeout",
    descr = "The failover_timeout for mesos in seconds (default: 1 week)",
    default = Some(604800L))

  lazy val highlyAvailable = opt[Boolean]("ha",
    descr = "Runs Marathon in HA mode with leader election. " +
      "Allows starting an arbitrary number of other Marathons but all need " +
      "to be started in HA mode. This mode requires a running ZooKeeper",
    noshort = true, default = Some(true))

  lazy val checkpoint = opt[Boolean]("checkpoint",
    descr = "Enable checkpointing of tasks. " +
      "Requires checkpointing enabled on slaves. Allows tasks to continue " +
      "running during mesos-slave restarts and upgrades",
    noshort = true)

  lazy val localPortMin = opt[Int]("local_port_min",
    descr = "Min port number to use when assigning ports to apps",
    default = Some(10000))

  lazy val localPortMax = opt[Int]("local_port_max",
    descr = "Max port number to use when assigning ports to apps",
    default = Some(20000))

  lazy val defaultExecutor = opt[String]("executor",
    descr = "Executor to use when none is specified",
    default = Some("//cmd"))

  lazy val hostname = opt[String]("hostname",
    descr = "The advertised hostname stored in ZooKeeper so another standby " +
      "host can redirect to this elected leader",
    default = Some(java.net.InetAddress.getLocalHost().getHostName()))

  def executor: Executor = Executor.dispatch(defaultExecutor())

  lazy val mesosRole = opt[String]("mesos_role",
    descr = "Mesos role for this framework",
    default = None)

  lazy val taskLaunchTimeout = opt[Long]("task_launch_timeout",
    descr = "Time, in milliseconds, to wait for a task to enter " +
    "the TASK_RUNNING state before killing it",
    default = Some(60000L)) // 60 seconds

  lazy val taskRateLimit = opt[Long]("task_rate_limit",
    descr = "This is the time window within which instances may be launched " +
    "for a given app.  For example, if an app has 5 instances, it will " +
    "only launch 5 instances within 60s regardless of " +
    "whether they succeed or fail.",
    default = Some(60000L)) // 60 seconds

  lazy val reconciliationInitialDelay = opt[Long]("reconciliation_initial_delay",
    descr = "This is the length of time, in milliseconds, before Marathon " +
    "begins to periodically perform task reconciliation operations",
    default = Some(30000L)) // 30 seconds

  lazy val reconciliationFrequency = opt[Long]("reconciliation_frequency",
    descr = "This is the length of time, in milliseconds, between task " +
    "reconciliation operations.",
    default = Some(30000L)) // 30 seconds

  lazy val mesosUser = opt[String]("mesos_user",
    descr = "Mesos user for this framework",
    default = new SystemProperties().get("user.name")) // Current logged in user
}
