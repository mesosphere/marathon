package mesosphere.marathon.plugin

import javax.inject.Inject

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.EventStream
import com.google.inject.name.Named
import com.google.inject.{ AbstractModule, Provides }
import mesosphere.marathon.event.{ EventDelegate, EventModule }
import mesosphere.marathon.plugin.event.EventListener
import org.rogach.scallop.ScallopConf

trait PluginConfiguration extends ScallopConf {
  lazy val pluginDir = opt[String]("plugin-dir",
    descr = "Path to the local directory with plugin jars.",
    required = false,
    noshort = true)
}

class PluginModule(conf: PluginConfiguration) extends AbstractModule {

  override def configure(): Unit = conf.pluginDir.foreach { dir =>
    val pluginManager = PluginManager(dir)
    bind(classOf[PluginManager]).toInstance(pluginManager)
    bind(classOf[EagerDependency]).asEagerSingleton()
  }

  @Provides
  @Named(PluginModule.PluginEventDelegate)
  def provideEventDelegate(system: ActorSystem,
                           pluginManager: PluginManager,
                           @Named(EventModule.busName) stream: EventStream): ActorRef = {
    val listener = pluginManager.providers[EventListener]
    system.actorOf(Props(new EventDelegate(stream, listener)))
  }

}

object PluginModule {
  final val PluginEventDelegate = "EventDelegate"
}

//TODO: is there a better way of eager
private class EagerDependency @Inject() (@Named(PluginModule.PluginEventDelegate) ref: ActorRef)
