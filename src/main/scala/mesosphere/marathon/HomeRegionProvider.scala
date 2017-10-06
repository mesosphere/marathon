package mesosphere.marathon

/**
  * Returns value of home region for Marathon. This is strongly connected with Marathon being region aware.
  * The value is used for region-aware scheduling.
  */
trait HomeRegionProvider {
  def getHomeRegion(): Option[String]
}
