package mesosphere.marathon.core.autoscale.impl

import akka.actor._
import akka.pattern.pipe
import mesosphere.marathon.core.autoscale.impl.AutoScaleActor.{ AutoScaleFailure, AutoScaleSuccess }
import mesosphere.marathon.core.autoscale.impl.AutoScaleAppActor._
import mesosphere.marathon.core.autoscale.{ AutoScaleConfig, AutoScalePolicy, AutoScaleResult }
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, GroupManagement, Timestamp }
import mesosphere.marathon.upgrade.{ DeploymentPlan, DeploymentStep, ScaleApplication }
import mesosphere.marathon.{ ConflictingChangeException, MarathonSchedulerService }
import mesosphere.util.ThreadPoolContext

import scala.concurrent.Future

object AutoScaleAppActor {

  sealed trait AutoScaleActionResult
  case object NoActionNecessary extends AutoScaleActionResult
  case class DeploymentTriggered(result: AutoScaleResult, deployment: DeploymentPlan) extends AutoScaleActionResult
  case class DeploymentBlocked(result: AutoScaleResult) extends AutoScaleActionResult

  def props(app: AppDefinition,
            lastFailure: Option[Timestamp],
            handler: ActorRef,
            policies: Seq[AutoScalePolicy],
            groupManager: GroupManagement,
            scheduler: MarathonSchedulerService,
            taskTracker: TaskTracker,
            conf: AutoScaleConfig): Props = {
    Props(new AutoScaleAppActor(app, lastFailure, handler, policies, groupManager, scheduler, taskTracker, conf))
  }
}

class AutoScaleAppActor(app: AppDefinition,
                        lastFailure: Option[Timestamp],
                        handler: ActorRef,
                        policies: Seq[AutoScalePolicy],
                        groupManager: GroupManagement,
                        scheduler: MarathonSchedulerService,
                        taskTracker: TaskTracker,
                        conf: AutoScaleConfig) extends Actor with ActorLogging {

  require(app.autoScale.isDefined, s"App without autoScale definition: $app")
  require(app.autoScale.get.policies.nonEmpty, s"AutoScaling an application without policies is not possible: $app")

  private[this] var policyResults = List.empty[AutoScaleResult]
  implicit val ec = ThreadPoolContext.ioContext

  override def preStart(): Unit = queryAppPolicies()

  override def receive: Receive = {
    case result: AutoScaleResult =>
      policyResults ::= result
      if (policyResults.size == app.autoScale.map(_.policies.size).getOrElse(0)) scaleApp() pipeTo self
    case DeploymentBlocked(result) =>
      log.warning(s"Can not auto scale app ${app.id} to ${result.target} since there is a running deployment")
      handler ! AutoScaleFailure(app)
      context.stop(self)
    case DeploymentTriggered(result, deployment) =>
      log.info(s"Auto scale deployment for app ${app.id} triggered ($result). Deployment: ${deployment.id}")
      handler ! AutoScaleSuccess(app)
      context.stop(self)
    case NoActionNecessary =>
      log.debug(s"No auto scale action required for app ${app.id}.")
      handler ! AutoScaleSuccess(app)
      context.stop(self)
    case Status.Failure(f) =>
      log.error(f, s"Could not auto scale app ${app.id}")
      handler ! AutoScaleFailure(app)
      context.stop(self)
  }

  //scalastyle:off cyclomatic.complexity
  def scaleApp(): Future[AutoScaleActionResult] = {
    val scaleResult = {
      //we choose the policy with the highest impact
      def instanceDiff(result: AutoScaleResult) = math.abs(app.instances - result.target)
      policyResults.sortBy(instanceDiff).last
    }
    val instances = scaleResult.target
    val kill = scaleResult.killTasks

    def isDeployingWithScale: Future[Option[Boolean]] = {
      scheduler.listRunningDeployments().map { deployemnts =>
        deployemnts.find(i => i.plan.affects(app)).map(_.plan.steps) match {
          case Some(Seq(DeploymentStep(Seq(ScaleApplication(_, _, _))))) => Some(true)
          case Some(_) => Some(false)
          case None => None
        }
      }
    }
    def forceDeployment: Boolean = lastFailure.exists(_.until(Timestamp.now()) > conf.forceDeploymentTimeout)
    def update(force: Boolean): Future[AutoScaleActionResult] = {
      def updateApp(existing: Option[AppDefinition]): AppDefinition = {
        existing.foreach { existingApp =>
          if (existingApp.version != app.version)
            throw new ConflictingChangeException(s"App ${app.id}: ${app.version} ${existingApp.version}")
        }
        app.copy(instances = instances)
      }
      val update = groupManager.updateApp(app.id, updateApp, toKill = kill, force = force)
      update
        .map(DeploymentTriggered(scaleResult, _))
        .recover { case ConflictingChangeException(_) => DeploymentBlocked(scaleResult) }
    }
    if (app.instances != instances || kill.nonEmpty) {
      isDeployingWithScale.flatMap {
        case Some(true) if forceDeployment => update(true)
        case Some(_)                       => Future.successful(DeploymentBlocked(scaleResult))
        case None                          => update(false)
      }
    }
    else {
      Future.successful(NoActionNecessary)
    }
  }

  private[this] def queryAppPolicies(): Unit = {
    val me = self
    val tasks = taskTracker.appTasksSync(app.id).toSeq
    for {
      definition <- app.autoScale.map(_.policies).getOrElse(Seq.empty)
      policy <- policies.find(_.name == definition.name)
    } policy.scale(definition, app, tasks) pipeTo me
  }
}
