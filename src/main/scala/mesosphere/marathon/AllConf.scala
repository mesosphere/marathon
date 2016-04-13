package mesosphere.marathon

import mesosphere.chaos.AppConfiguration
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.plugin.PluginManagerConfiguration
import mesosphere.marathon.event.EventConfiguration
import mesosphere.marathon.event.http.HttpEventConfiguration
import mesosphere.marathon.metrics.MetricsReporterConf
import org.rogach.scallop.ScallopConf

import scala.reflect.runtime.universe._

class AllConf(args: Seq[String] = Nil) extends ScallopConf(args)
  with MetricsReporterConf
  with HttpConf
  with MarathonConf
  with AppConfiguration
  with EventConfiguration
  with HttpEventConfiguration
  with DebugConf
  with PluginManagerConfiguration

object AllConf {

  def option[T](prop: String)(implicit typeTag: TypeTag[T]): Option[T] = {
    val conf = testConfig.getOrElse(Main.conf)
    if (conf.builder.isSupplied(prop)) conf.builder.get[T](prop) else None
  }

  def enabledFeatures: Set[String] =
    option[String]("enable_features")
      .map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet)
      .getOrElse(Set.empty)

  def isFeatureSet(feature: String): Boolean = enabledFeatures(feature)

  /**
    * We use a var here, in order to enable tests to use a specific configuration.
    */
  @volatile var testConfig: Option[ScallopConf] = None
  def withTestConfig(args: Seq[String], withDefault: Boolean = true): Unit = {
    val result = if (withDefault) Seq("--master", "local") ++ args else args
    val conf = new AllConf(result)
    conf.afterInit()
    testConfig = Some(conf)
  }
}
