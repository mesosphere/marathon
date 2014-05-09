package mesosphere.marathon.upgrade

import com.google.inject.Singleton
import javax.inject.{Named, Inject}
import mesosphere.marathon.event.{UpgradeSuccess, EventModule}
import com.google.common.eventbus.EventBus
import akka.actor.{Props, ActorSystem}
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.{event, MarathonSchedulerService}
import scala.concurrent.{Promise, Future}
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.api.v2.Group
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global


/**
 * The UpgradeManager can deploy an existing version of a product to a new version of the same product.
 */
class UpgradeManager @Singleton @Inject() (
  system:ActorSystem,
  @Named(EventModule.busName) eventBus: Option[EventBus],
  scheduler: MarathonSchedulerService,
  taskTracker: TaskTracker,
  healthCheck: HealthCheckManager
) {

  /**
   * Install a new product.
   * @return a future which gets completed, when the installation has finished
   */
  def install(product: Group): Future[Group] = {
    val promise = eventHandlingPromise(product, "install")
    Props(classOf[InstallActor], product, promise, scheduler, taskTracker, healthCheck)
    promise.future
  }

  /**
   * Upgrade an existing product
   * @return a future that gets completed, when the upgrade has finished
   */
  def upgrade(oldProduct: Group, newProduct: Group): Future[Group] = {
    val promise = eventHandlingPromise(newProduct, "upgrade")
    Props(classOf[UpgradeActor], oldProduct, newProduct, promise, scheduler, taskTracker, healthCheck)
    promise.future
  }

  /**
   * Delete an existing product.
   * @return a future that gets completed, when the deletion has finished
   */
  def delete(product: Group): Future[Group] = {
    val promise = eventHandlingPromise(product, "delete")
    Props(classOf[DeleteActor], product, promise, scheduler, taskTracker, healthCheck)
    promise.future
  }

  /**
   * Called when the system starts or this instance is elected as leader.
   */
  def onStart(): Unit = ???

  /**
   * Called when the system stops or this instance is defeated as leader.
   */
  def onStop(): Unit = ???

  private def eventHandlingPromise(group:Group, kind:String) : Promise[Group] = {
    val promise = Promise[Group]()
    promise.future.onComplete {
      case Success(_) => eventBus.foreach(_.post(UpgradeSuccess(group, kind)))
      case Failure(_) => eventBus.foreach(_.post(event.UpgradeFailed(group, kind)))
    }
    promise
  }
}
