package mesosphere.marathon.plugin

import java.io.File
import java.net.{ URL, URLClassLoader }
import java.util.ServiceLoader

import mesosphere.marathon.io.IO
import org.apache.log4j.Logger

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
  * Instances of this class holds a reference to a plugin of a specific type.
  * @param classTag the class tag of the specific type
  * @param serviceLoader the service loader, that was used to instantiate the plugin instances
  * @param instances all the instances of this plugin
  * @tparam T the specific type of this plugin.
  */
case class PluginReference[T](classTag: ClassTag[T], serviceLoader: ServiceLoader[T], instances: Seq[T])

/**
  * The plugin manager can load plugins from given urls.
  * @param urls the urls pointing to plugins.
  */
class PluginManager(val urls: Seq[URL]) {

  private[this] val log = Logger.getLogger(getClass.getName)

  private[this] var plugins = List.empty[PluginReference[_]]

  val classLoader = new URLClassLoader(urls.toArray, this.getClass.getClassLoader)

  /**
    * Load plugin for a specific type.
    */
  private[this] def load[T <: Plugin](implicit ct: ClassTag[T]): PluginReference[T] = {
    val serviceLoader = ServiceLoader.load(ct.runtimeClass.asInstanceOf[Class[T]], classLoader)
    val instances = serviceLoader.iterator().asScala.toSeq
    log.info(
      s"""Load plugin ${ct.runtimeClass.getName} from this locations: ${urls.mkString(", ")}.
         |Found ${instances.size} instances.""".stripMargin)
    PluginReference(ct, serviceLoader, instances)
  }

  /**
    * Get all service provider that can be found in the plugin directory for the given type.
    * Each plugin type is loaded once and get cached.
    * @return the list of all service provider for the given type.
    */
  def providers[T <: Plugin](implicit sc: ClassTag[T]): Seq[T] = synchronized {
    def loadAndAdd: PluginReference[T] = {
      val plugin = load[T]
      plugins ::= plugin
      plugin
    }
    plugins
      .find(_.classTag == sc)
      .map(_.asInstanceOf[PluginReference[T]])
      .getOrElse(loadAndAdd)
      .instances
  }

  def provider[T <: Plugin](implicit sc: ClassTag[T]): Option[T] = providers[T].headOption

  def withProvider[T <: Plugin](fn: Seq[T] => Unit)(implicit ct: ClassTag[T]): Unit = {
    val p = providers[T]
    if (p.nonEmpty) fn(p)
  }

  def installedPlugins: Seq[PluginReference[_]] = plugins
}

object PluginManager extends IO {
  def apply(dir: String): PluginManager = apply(new File(dir))
  def apply(dir: File): PluginManager = {
    val jars = listFiles(dir, ".*jar$".r)
    new PluginManager(jars.map(_.toURI.toURL))
  }
}
