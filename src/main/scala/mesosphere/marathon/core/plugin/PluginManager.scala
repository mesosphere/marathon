package mesosphere.marathon.core.plugin

import scala.reflect.ClassTag

trait PluginManager {

  def plugins[T](implicit ct: ClassTag[T]): Seq[T]

  def definitions: PluginDefinitions

}

object PluginManager {
  lazy val None = new PluginManager {
    override def plugins[T](implicit ct: ClassTag[T]): Seq[T] = Seq.empty[T]
    override def definitions = PluginDefinitions.None
  }
}
