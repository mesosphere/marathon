package mesosphere.marathon

trait HomeRegionProvider {
  def getHomeRegion(): Option[String]
}
