package mesosphere.marathon.plugin

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

import mesosphere.marathon.io.IO
import mesosphere.marathon.plugin.event.EventListener

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

case class PluginReference[T](classTag: ClassTag[T], serviceLoader: ServiceLoader[T], instances: Seq[T])

class PluginManager(dir: File) extends IO {

  val jars = listFiles(dir, ".*jar$".r)
  val classLoader = new URLClassLoader(listFiles(dir, ".*jar$".r).map(_.toURI.toURL), this.getClass.getClassLoader)

  private[this] var plugins = List.empty[PluginReference[_]]

  private[this] def load[T <: Plugin](implicit sc: ClassTag[T]): PluginReference[T] = synchronized {
    val serviceLoader = ServiceLoader.load(sc.runtimeClass.asInstanceOf[Class[T]], classLoader)
    val instances = serviceLoader.iterator().asScala.toSeq
    val plugin = PluginReference(sc, serviceLoader, instances)
    plugins ::= plugin
    plugin
  }

  def instances[T <: Plugin](implicit sc: ClassTag[T]): Seq[T] = synchronized {
    plugins
      .find(_.classTag == sc)
      .map(_.asInstanceOf[PluginReference[T]])
      .getOrElse(load)
      .instances
  }

  def instance[T <: Plugin](implicit sc: ClassTag[T]): Option[T] = instances[T].headOption

  def installedPlugins = plugins
}

object PluginManager {

  def main(args: Array[String]) {
    val manager = new PluginManager(new File("/Users/matthias/"))
    println(">>> " + manager.jars.mkString(", "))
    manager.instance[EventListener].foreach { listener =>
      listener.handleEvent("Test a")
      listener.handleEvent("Test b")
      listener.handleEvent("Test c")
    }
    manager.instance[EventListener].foreach { listener =>
      listener.handleEvent("Test a")
      listener.handleEvent("Test b")
      listener.handleEvent("Test c")
    }
  }
}
