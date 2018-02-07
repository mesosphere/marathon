package mesosphere.marathon
package core.appinfo

import org.rogach.scallop.ScallopConf

trait AppInfoConfig extends ScallopConf {

  lazy val appInfoModuleExecutionContextSize = opt[Int](
    "app_info_module_execution_context_size",
    default = Some(8),
    hidden = true,
    descr = "INTERNAL TUNING PARAMETER: AppInfo module's execution context thread pool size"
  )

  lazy val defaultInfoServiceExecutionContextSize = opt[Int](
    "default_info_service_execution_context_size",
    default = Some(8),
    hidden = true,
    descr = "INTERNAL TUNING PARAMETER: DefaultInfo service's execution context thread pool size"
  )
}