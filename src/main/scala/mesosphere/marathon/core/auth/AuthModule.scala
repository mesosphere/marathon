package mesosphere.marathon.core.auth

import mesosphere.marathon.WrongConfigurationException
import mesosphere.marathon.core.auth.impl.AuthAllowEverything
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer }

import scala.reflect.ClassTag

class AuthModule(pluginManager: PluginManager) {

  private[this] def pluginOption[T](implicit ct: ClassTag[T]): Option[T] = {
    val plugins = pluginManager.plugins[T]
    if (plugins.size > 1) throw new WrongConfigurationException(
      s"Only one plugin expected for ${ct.runtimeClass.getName}, but found: ${plugins.map(_.getClass.getName)}"
    )
    plugins.headOption
  }

  lazy val authorizer: Authorizer = pluginOption[Authorizer].getOrElse(AuthAllowEverything)
  lazy val authenticator: Authenticator = pluginOption[Authenticator].getOrElse(AuthAllowEverything)
}
