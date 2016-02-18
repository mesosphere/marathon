package mesosphere.marathon.core.plugin

import scala.reflect.ClassTag

trait PluginManager {

  def plugins[T](implicit ct: ClassTag[T]): Seq[T]

  def definitions: PluginDefinitions

}
