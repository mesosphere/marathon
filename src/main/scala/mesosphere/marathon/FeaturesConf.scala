package mesosphere.marathon

import org.rogach.scallop.{ScallopConf, ValueConverter}

trait FeaturesConf extends ScallopConf {
  import DeprecatedFeatures.DeprecatedFeature
  lazy val features = opt[String](
    "enable_features",
    descr = s"A comma-separated list of features. Available features are: ${Features.description}",
    required = false,
    default = None,
    noshort = true,
    validate = validateFeatures
  )

  private val deprecatedFeatureParser = implicitly[ValueConverter[String]].map { str =>
    val parsed = parseList(str).map { key =>
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
    parsed.collect { case (_, Some(df)) => df }
  }

  lazy val deprecatedFeatures = opt[Set[DeprecatedFeature]](
    "deprecated_features",
    descr = "A comma-separated list of deprecated features to continue to enable",
    required = false,
    default = Some(Set.empty),
    noshort = true,
    validate = validateDeprecatedFeatures)(deprecatedFeatureParser)

  lazy val availableFeatures: Set[String] = features.map(parseList).getOrElse(Set.empty)

  private[this] def validateFeatures(str: String): Boolean = {
    val parsed = parseList(str)
    // throw exceptions for better error messages
    val unknownFeatures = parsed.filter(!Features.availableFeatures.contains(_))
    lazy val unknownFeaturesString = unknownFeatures.mkString(", ")
    require(
      unknownFeatures.isEmpty,
      s"Unknown features specified: $unknownFeaturesString. Available features are: ${Features.description}"
    )
    true
  }

  def isFeatureSet(name: String): Boolean = availableFeatures.contains(name)

  private[this] def parseList(str: String): Set[String] =
    str.split(',').map(_.trim).filter(_.nonEmpty).toSet

  private[this] def validateDeprecatedFeatures(dfs: Iterable[DeprecatedFeature]): Boolean = {
    DeprecatedFeatures.warnOrFail(dfs)
    true
  }
}
