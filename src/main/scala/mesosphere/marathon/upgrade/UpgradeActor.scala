package mesosphere.marathon.upgrade

import akka.actor.Actor
import mesosphere.marathon.{UpgradeFailed, MarathonSchedulerService}
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.tasks.TaskTracker
import scala.concurrent.Promise
import mesosphere.marathon.api.v2.{AppUpdate, Group}
import scala.concurrent.duration._
import org.apache.log4j.Logger
import mesosphere.marathon.upgrade.UpgradeAction._

/**
 * The UpgradeActor
 */
trait UpgradeAction { this: Actor =>

  private[this] val log = Logger.getLogger(getClass.getName)

  //dependencies
  def scheduler: MarathonSchedulerService
  def taskTracker: TaskTracker
  def healthCheck: HealthCheckManager
  def promise: Promise[Group]

  def expectedTasksRunning(appId: String, count: Int): Boolean = {
    val countReached = taskTracker.count(appId) == count
    val healthy = true //TODO: health check?
    countReached && healthy
  }


  def succeed(product:Group): Unit = {
    promise.success(product)
    context.stop(self)
  }

  def fail(): Unit = {
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
) extends Actor with UpgradeAction {

  import context.dispatcher

  override def preStart(): Unit = self ! Start

  def receive: Receive = {
    case Start =>
      product.apps.foreach(scheduler.startApp)
      context.system.scheduler.scheduleOnce(product.scalingStrategy.watchPeriod seconds, self, WatchTimeout)
      context.become(staged)
  }

  def staged: Receive = {
    case WatchTimeout =>
      val expected = product.apps.forall(app => expectedTasksRunning(app.id, app.instances))
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
) extends Actor with UpgradeAction {

  import context.dispatcher

  var plan = DeploymentPlan(oldProduct, newProduct, Map.empty[String, List[String]])

  override def preStart(): Unit = self ! Start

  def applyCurrentStep(): Unit = plan.current.foreach { step =>
    step.deployments.foreach { action =>
      scheduler.updateApp(action.appId, AppUpdate(instances = Some(action.scale)))
    }
  }

  def currentStepReached: Boolean = ???

  def nextStep(): Unit = {
    require(plan.hasNext)
    plan = plan.next
    applyCurrentStep()
    context.system.scheduler.scheduleOnce(newProduct.scalingStrategy.watchPeriod seconds, self, WatchTimeout)
    if (!plan.hasNext) context.become(staged)
  }

  override def fail(): Unit = {
    //TODO: rescale old product to old values
    super.fail()
  }

  def receive: Receive = {
    case Start if plan.hasNext =>
      plan = plan.next
      nextStep()
      context.become(scaling)
    case Start => succeed(newProduct) //empty plan
  }

  def scaling: Receive = {
    case WatchTimeout => if (currentStepReached) nextStep() else fail()
  }

  def staged: Receive = {
    case WatchTimeout => if (currentStepReached) succeed(newProduct) else fail()
  }
}

class DeleteActor (
  val product: Group,
  val promise: Promise[Group],
  val scheduler: MarathonSchedulerService,
  val taskTracker: TaskTracker,
  val healthCheck: HealthCheckManager
) extends Actor with UpgradeAction {

  override def preStart(): Unit = self ! Start
  def receive: Receive = {
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
