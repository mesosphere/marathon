package mesosphere.marathon.core.autoscale.impl

import akka.actor.ActorRefFactory
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.autoscale.{ AutoScaleResult, AutoScalePolicy }
import mesosphere.marathon.state.{ AutoScalePolicyDefinition, AppDefinition }
import mesosphere.mesos.{ MesosAgentData, Constraints }
import mesosphere.util.Caching
import mesosphere.util.state.MesosLeaderInfo
import org.slf4j.LoggerFactory
import play.api.libs.json.Json
import spray.client.pipelining._
import spray.http._

import scala.concurrent.Future
import scala.concurrent.duration._

class MesosAgentCountScalePolicy(leaderInfo: MesosLeaderInfo, implicit val factory: ActorRefFactory)
    extends AutoScalePolicy with Caching[Future[Seq[MesosAgentData]]] {

  private[this] val log = LoggerFactory.getLogger(getClass)
  private[this] implicit val ec = factory.dispatcher
  override protected[this] def cacheExpiresAfter: FiniteDuration = 1.minute

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
                            slaves: Seq[MesosAgentData]): AutoScaleResult = {

    val matchingSlaves = Constraints.selectMatchingSlaves(slaves.filter(_.active), app.constraints)
    lazy val matchingSlaveIds = matchingSlaves.map(_.id).toSet
    val tasksToKill = tasks.filterNot(task => matchingSlaveIds.contains(task.getSlaveId.getValue))
    val active = matchingSlaveIds.size
    AutoScaleResult(active, tasksToKill)
  }

  private[this] def readSlaveData: HttpResponse => Seq[MesosAgentData] = { response =>
    val slaves = Json.parse(response.entity.asString) \ "slaves"
    slaves.as[Seq[MesosAgentData]]
  }

  private[this] def slaveData(base: String): Future[Seq[MesosAgentData]] = cached(name) {
    val url = if (base.endsWith("/")) s"${base}slaves" else s"$base/slaves"
    log.info(s"Try to get slave info from: $url")
    val pipeline = sendReceive ~> readSlaveData
    pipeline(Get(url))
  }
}
