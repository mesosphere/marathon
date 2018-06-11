package mesosphere.marathon

import org.rogach.scallop.{ScallopConf, ValueConverter}

trait FeaturesConf extends ScallopConf {
  private val setStringParser: ValueConverter[Set[String]] = implicitly[ValueConverter[String]].map(parseList(_))

  private val deprecatedFeatureParser: ValueConverter[DeprecatedFeatureSet] = setStringParser.map { values =>
    val parsed = values.map { key =>
      (key, DeprecatedFeatures.all.find(_.key == key))
    }

    val unknown = parsed.collect { case (key, None) => key }
    require(
      unknown.isEmpty,
      s"Unknown deprecated features specified: ${unknown.mkString(", ")}. Available deprecated features are:\n\n${DeprecatedFeatures.description}" +
        "\n\n" +
        "If you recently upgraded, you should downgrade to the old Marathon version and remove the deprecated " +
        "feature(s) in question, ensuring that your cluster continues to function without it."
    )
    val dfs = parsed.collect { case (_, Some(df)) => df }
    DeprecatedFeatureSet(BuildInfo.version, dfs)
  }

  lazy val features = opt[Set[String]](
    "enable_features",
    descr = s"A comma-separated list of features. Available features are: ${Features.description}",
    required = false,
    default = Some(Set.empty),
    noshort = true,
    validate = validateFeatures
  )(setStringParser)

  lazy val deprecatedFeatures = opt[DeprecatedFeatureSet](
    "deprecated_features",
    descr = "A comma-separated list of deprecated features to continue to enable",
    required = false,
    default = Some(DeprecatedFeatureSet(BuildInfo.version, Set.empty)),
    noshort = true,
    validate = { dfs => dfs.isValid() })(deprecatedFeatureParser)

  def availableFeatures: Set[String] = features()

  def isFeatureSet(name: String): Boolean = availableFeatures.contains(name)

  private[this] def validateFeatures(features: Set[String]): Boolean = {
    // throw exceptions for better error messages
    val unknownFeatures = features.filter(!Features.availableFeatures.contains(_))
    lazy val unknownFeaturesString = unknownFeatures.mkString(", ")
    require(
      unknownFeatures.isEmpty,
      s"Unknown features specified: $unknownFeaturesString. Available features are: ${Features.description}"
    )
    true
  }

  private[this] def parseList(str: String): Set[String] =
    str.split(',').map(_.trim).filter(_.nonEmpty).toSet
}
