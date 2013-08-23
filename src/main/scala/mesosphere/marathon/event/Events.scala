package mesosphere.marathon.event

import mesosphere.marathon.api.v1.AppDefinition
import org.rogach.scallop.ScallopConf
import com.google.inject.{Singleton, Provides, AbstractModule}
import com.google.common.eventbus.{AsyncEventBus, EventBus}
import java.util.concurrent.Executors
import javax.inject.Named
import java.util.logging.Logger

trait EventSubscriber[C <: ScallopConf, M <: AbstractModule] {
  def configuration(): Class[C]
  def module(): Option[Class[M]]
}

//TODO(FL): Wire this up such that events are optional.
trait EventConfiguration extends ScallopConf {

  lazy val eventSubscriber = opt[String]("event_subscriber",
    descr = "e.g. http_callback",
    required = false,
    noshort = true)
}

class EventModule(conf: EventConfiguration) extends AbstractModule {

  val log = Logger.getLogger(getClass.getName)
  def configure() {}

  @Named(EventModule.busName)
  @Provides
  @Singleton
  def provideEventBus(): Option[EventBus] = {
    if (conf.eventSubscriber.isSupplied) {
      log.info("Creating Event Bus.")
      val pool =  Executors.newCachedThreadPool()
      val bus = new AsyncEventBus(pool)
      Some(bus)
    } else {
      None
    }
  }
}

object EventModule {
  final val busName = "events"
}

trait MarathonEvent {
  def eventType = "marathon_event"
}

case class ApiPostEvent(
    val clientIp: String,
    val uri: String,
    val appDefinition: AppDefinition,
    override val eventType: String = "api_post_event")
  extends MarathonEvent

case class MesosStatusUpdateEvent(
    val taskId: String,
    val taskStatus: Int,
    override val eventType: String = "status_update_event")
  extends MarathonEvent

