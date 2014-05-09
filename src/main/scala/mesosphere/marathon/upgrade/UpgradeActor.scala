package mesosphere.marathon.upgrade

import akka.actor.Actor
import mesosphere.marathon.{UpgradeFailed, MarathonSchedulerService}
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.tasks.TaskTracker
import scala.concurrent.Promise
import mesosphere.marathon.api.v2.{AppUpdate, Group}
import scala.concurrent.duration._
import mesosphere.marathon.api.v1.AppDefinition
import org.apache.log4j.Logger
import mesosphere.marathon.upgrade.UpgradeAction._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * The UpgradeActor
 */
trait UpgradeAction extends Actor {

  private val log = Logger.getLogger(getClass.getName)

  //dependencies
  def scheduler: MarathonSchedulerService
  def taskTracker: TaskTracker
  def healthCheck: HealthCheckManager
  def promise: Promise[Group]

  def expectedTasksRunning(app:AppDefinition, count:Int) : Boolean = {
    val countReached = taskTracker.count(app.id) == count
    val healthy = true //TODO: health check?
    countReached && healthy
  }


  def succeed(product:Group) : Unit = {
    promise.success(product)
    context.stop(self)
  }

  def fail() : Unit = {
    promise.failure(UpgradeFailed)
    context.stop(self)
  }
}

class InstallActor (
  val product: Group,
  val promise: Promise[Group],
  val scheduler: MarathonSchedulerService,
  val taskTracker: TaskTracker,
  val healthCheck: HealthCheckManager
) extends UpgradeAction {

  override def preStart(): Unit = self ! Start

  override def receive : Receive = {
    case Start =>
      product.apps.foreach(scheduler.startApp)
      context.system.scheduler.scheduleOnce(product.scaleUpStrategy.startupTimeout seconds, self, WatchTimeout)
      context.become(staged)
  }

  def staged : Receive = {
    case WatchTimeout =>
      val expected = product.apps.forall(app => expectedTasksRunning(app, app.instances))
      if (expected) succeed(product) else fail()
  }
}

class UpgradeActor (
  val oldProduct: Group,
  val newProduct: Group,
  val promise: Promise[Group],
  val scheduler: MarathonSchedulerService,
  val taskTracker: TaskTracker,
  val healthCheck: HealthCheckManager
) extends UpgradeAction {

  var plan = DeploymentPlan(oldProduct, newProduct)

  override def preStart(): Unit = self ! Start

  def applyCurrentStep() : Unit = plan.current.foreach { step =>
    step.deployments.foreach {
      case UpScaleAction(appId, count) => scheduler.updateApp(appId, AppUpdate(instances = Some(count)))
      case DownScaleAction(appId, count) => //TODO: tasktracker
    }
  }

  def currentStepReached : Boolean = ???

  def nextStep(): Unit = {
    require(plan.hasNext)
    plan = plan.next
    applyCurrentStep()
    context.system.scheduler.scheduleOnce(newProduct.scaleUpStrategy.startupTimeout seconds, self, WatchTimeout)
    if (!plan.hasNext) context.become(staged)
  }

  override def fail(): Unit = {
    //TODO: rescale old product to old values
    super.fail()
  }

  override def receive : Receive = {
    case Start if plan.hasNext =>
      plan = plan.next
      nextStep()
      context.become(scaling)
    case Start => succeed(newProduct) //empty plan
  }

  def scaling : Receive = {
    case WatchTimeout => if (currentStepReached) nextStep() else fail()
  }

  def staged : Receive = {
    case WatchTimeout => if (currentStepReached) succeed(newProduct) else fail()
  }
}

class DeleteActor (
  val product: Group,
  val promise: Promise[Group],
  val scheduler: MarathonSchedulerService,
  val taskTracker: TaskTracker,
  val healthCheck: HealthCheckManager
) extends UpgradeAction {

  override def preStart(): Unit = self ! Start
  override def receive: Receive = {
    case Start =>
      product.apps.foreach(scheduler.stopApp)
      succeed(product)
  }
}

object UpgradeAction {
  sealed trait UpgradeMessage
  case object WatchTimeout extends UpgradeMessage
  case object Start
}
