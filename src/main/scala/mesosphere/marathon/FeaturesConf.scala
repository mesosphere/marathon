package mesosphere.marathon

import org.rogach.scallop.{ ScallopConf, ScallopOption }

trait FeaturesConf extends ScallopConf {

  /**
    * Indicates the http backend to use
    */
  lazy val featureAkkaHttpServiceBackend: ScallopOption[Boolean] = opt[Boolean](
    "feature_akka_http_service_backend",
    descr = "Enabled the new Akka HTTP Service Backend",
    default = Some(false),
    required = false,
    noshort = true,
    hidden = true
  )
}
