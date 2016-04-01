package mesosphere.marathon.core.plugin.impl

import java.net.{ URL, URLClassLoader }
import java.util.ServiceLoader

import mesosphere.marathon.core.plugin.impl.PluginManagerImpl._
import mesosphere.marathon.core.plugin.{ PluginDefinition, PluginDefinitions, PluginManager }
import mesosphere.marathon.io.IO
import mesosphere.marathon.plugin.plugin.PluginConfiguration
import mesosphere.marathon.{ MarathonConf, WrongConfigurationException }
import org.slf4j.{ Logger, LoggerFactory }
import play.api.libs.json.{ JsObject, JsString, Json }

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
  * The plugin manager can load plugins from given urls.
  * @param urls the urls pointing to plugins.
  */
private[plugin] class PluginManagerImpl(val config: MarathonConf,
                                        val definitions: PluginDefinitions,
                                        val urls: Seq[URL]) extends PluginManager {
  private[this] val log: Logger = LoggerFactory.getLogger(getClass)

  private[this] var pluginHolders: List[PluginHolder[_]] = List.empty[PluginHolder[_]]

  val classLoader: URLClassLoader = new URLClassLoader(urls.toArray, this.getClass.getClassLoader)

  /**
    * Load plugin for a specific type.
    */
  private[this] def load[T](implicit ct: ClassTag[T]): PluginHolder[T] = {
    log.info(s"Loading plugins implementing '${ct.runtimeClass.getName}' from these urls: [${urls.mkString(", ")}]")
    def configure(plugin: T, definition: PluginDefinition): T = plugin match {
      case cf: PluginConfiguration if definition.configuration.isDefined =>
        log.info(s"Configure the plugin with this configuration: ${definition.configuration}")
        cf.initialize(Map("frameworkName" -> config.frameworkName()), definition.configuration.get)
        plugin
      case _ => plugin
    }
    val serviceLoader = ServiceLoader.load(ct.runtimeClass.asInstanceOf[Class[T]], classLoader)
    val providers = serviceLoader.iterator().asScala.toSeq
    val plugins = definitions.plugins.filter(_.plugin == ct.runtimeClass.getName).map { definition =>
      providers
        .find(_.getClass.getName == definition.implementation)
        .map(plugin => PluginReference(configure(plugin, definition), definition))
        .getOrElse(throw new WrongConfigurationException(s"Plugin not found: $definition"))
    }
    log.info(s"Found ${plugins.size} plugins.")
    PluginHolder(ct, plugins)
  }

  /**
    * Get all the service providers that can be found in the plugin directory for the given type.
    * Each plugin is loaded once and gets cached.
    * @return the list of all service providers for the given type.
    */
  def plugins[T](implicit ct: ClassTag[T]): Seq[T] = synchronized {
    def loadAndAdd: PluginHolder[T] = {
      val pluginHolder: PluginHolder[T] = load[T]
      pluginHolders ::= pluginHolder
      pluginHolder
    }

    pluginHolders
      .find(_.classTag == ct)
      .map(_.asInstanceOf[PluginHolder[T]])
      .getOrElse(loadAndAdd)
      .plugins.map(_.plugin)
  }
}

object PluginManagerImpl {
  case class PluginReference[T](plugin: T, definition: PluginDefinition)
  case class PluginHolder[T](classTag: ClassTag[T], plugins: Seq[PluginReference[T]])
  implicit val definitionFormat = Json.format[PluginDefinition]

  def parse(fileName: String): PluginDefinitions = {
    val plugins = Json.parse(IO.readFile(fileName)).as[JsObject]
      .\("plugins").as[JsObject]
      .fields.map { case (id, value) => JsObject(value.as[JsObject].fields :+ ("id" -> JsString(id))) }
      .map(_.as[PluginDefinition])
    PluginDefinitions(plugins)
  }

  private[plugin] def apply(conf: MarathonConf): PluginManagerImpl = {
    val configuredPluginManager = for {
      dirName <- conf.pluginDir
      confName <- conf.pluginConf
    } yield {
      val sources = IO.listFiles(dirName)
      val descriptor = parse(confName)
      new PluginManagerImpl(conf, descriptor, sources.map(_.toURI.toURL))
    }

    configuredPluginManager.get.getOrElse(new PluginManagerImpl(conf, PluginDefinitions(Seq.empty), Seq.empty))
  }
}

