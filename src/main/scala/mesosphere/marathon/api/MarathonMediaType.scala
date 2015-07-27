package mesosphere.marathon.api

object MarathonMediaType {
  /**
    * JSON media type plus a weight which prefers this media type over alternatives if there are multiple
    * matches and no other has a qs value of >= 2.
    *
    * Related issue: https://github.com/mesosphere/marathon/issues/1647
    *
    * Further information: http://scribbles.fried.se/2011/04/browser-views-in-jersey-and-fed-up.html
    * A higher "qs" value indicates a higher precedence if there are multiple handlers
    * with a matching @Produces value.
    */
  final val PREFERRED_APPLICATION_JSON = "application/json;qs=2"
}
