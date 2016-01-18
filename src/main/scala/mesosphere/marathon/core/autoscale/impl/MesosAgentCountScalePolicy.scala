package mesosphere.marathon.core.autoscale.impl

import akka.actor.ActorRefFactory
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.core.autoscale.{ AutoScaleResult, AutoScalePolicy }
import mesosphere.marathon.state.{ AutoScalePolicyDefinition, AppDefinition }
import mesosphere.mesos.{ Constraints, MesosSlaveData }
import mesosphere.util.ThreadPoolContext
import mesosphere.util.state.MesosLeaderInfo
import org.slf4j.LoggerFactory
import play.api.libs.json.Json
import spray.client.pipelining._
import spray.http._

import scala.concurrent.Future
import scala.util.control.NonFatal

class MesosAgentCountScalePolicy(leaderInfo: MesosLeaderInfo, implicit val factory: ActorRefFactory)
    extends AutoScalePolicy {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] implicit val ec = ThreadPoolContext.ioContext

  override val name: String = "mesos_agent_count"

  override def scale(definition: AutoScalePolicyDefinition,
                     app: AppDefinition,
                     tasks: Seq[MarathonTask]): Future[AutoScaleResult] = {
    leaderInfo.currentLeaderUrl
      .map { leaderUrl => slaveData(leaderUrl).map(scaleTo(definition, app, tasks, _)) }
      .getOrElse(Future.successful(AutoScaleResult(app.instances, Seq.empty)))
  }

  private[this] def scaleTo(definition: AutoScalePolicyDefinition,
                            app: AppDefinition,
                            tasks: Seq[MarathonTask],
                            slaves: Seq[MesosSlaveData]): AutoScaleResult = {

    val matchingSlaves = Constraints.selectMatchingSlaves(slaves.filter(_.active), app.constraints)
    lazy val matchingSlaveIds = matchingSlaves.map(_.id).toSet
    val tasksToKill = tasks.filterNot(task => matchingSlaveIds.contains(task.getSlaveId.getValue))
    val active = matchingSlaveIds.size
    AutoScaleResult(active, tasksToKill)
  }

  private[this] def readSlaveData: HttpResponse => Seq[MesosSlaveData] = { response =>
    import Formats.MesosSlaveDataFormat
    val slaves = Json.parse(response.entity.asString) \ "slaves"
    slaves.as[Seq[MesosSlaveData]]
  }

  private[this] def slaveData(base: String): Future[Seq[MesosSlaveData]] = {
    val url = if (base.endsWith("/")) s"${base}slaves" else s"$base/slaves"
    log.info(s"Try to get slave info from: $url")
    val pipeline = sendReceive ~> readSlaveData
    val result = pipeline(Get(url))
    result.onFailure {
      case NonFatal(ex) => log.error(s"Error requesting $url", ex)
    }
    result
  }
}
