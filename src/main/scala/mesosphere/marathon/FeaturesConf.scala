package mesosphere.marathon

import org.rogach.scallop.ScallopConf

trait FeaturesConf extends ScallopConf {
  lazy val features = opt[String](
    "enable_features",
    descr = s"A comma-separated list of features. Available features are: ${Features.description}",
    required = false,
    default = None,
    noshort = true,
    validate = validateFeatures
  )

  lazy val deprecatedFeatureKeys = opt[String](
    "deprecated_features",
    descr = "A comma-separated list of deprecated features to continue to enable",
    required = false,
    default = None,
    noshort = true,
    validate = validateDeprecatedFeatures)

  lazy val availableFeatures: Set[String] = features.map(parseFeatures).getOrElse(Set.empty)

  lazy val deprecatedFeatures: Set[DeprecatedFeatures.DeprecatedFeature] = deprecatedFeatureKeys.toOption match {
    case Some(dfKeys) =>
      val f = parseFeatures(dfKeys).map { dfKey =>
        DeprecatedFeatures.all.find(_.key == dfKey).getOrElse { throw new RuntimeException("We should not see this") }
      }
      DeprecatedFeatures.warnOrFail(f)
      f
    case None =>
      Set.empty
  }

  private[this] def validateFeatures(str: String): Boolean = {
    val parsed = parseFeatures(str)
    // throw exceptions for better error messages
    val unknownFeatures = parsed.filter(!Features.availableFeatures.contains(_))
    lazy val unknownFeaturesString = unknownFeatures.mkString(", ")
    require(
      unknownFeatures.isEmpty,
      s"Unknown features specified: $unknownFeaturesString. Available features are: ${Features.description}"
    )
    true
  }

  private def validateDeprecatedFeatures(str: String): Boolean = {
    val parsed = parseFeatures(str).map { key =>
      (key, DeprecatedFeatures.all.find(_.key == key))
    }

    val unknown = parsed.collect { case (key, None) => key }
    require(
      unknown.isEmpty,
      s"Unknown deprecated features specified: ${unknown.mkString(", ")}. Available deprecated features are: ${Features.description}" +
        "\n\n" +
        "If you recently upgraded, you should downgrade to the old Marathon version and remove the deprecated " +
        "feature(s) in question, ensuring that your cluster continues to function without it."
    )
    true
  }

  def isFeatureSet(name: String): Boolean = availableFeatures.contains(name)

  def isDeprecatedFeatureSet(deprecatedFeature: DeprecatedFeatures.DeprecatedFeature): Boolean =
    deprecatedFeatures.contains(deprecatedFeature)

  private[this] def parseFeatures(str: String): Set[String] =
    str.split(',').map(_.trim).filter(_.nonEmpty).toSet
}
