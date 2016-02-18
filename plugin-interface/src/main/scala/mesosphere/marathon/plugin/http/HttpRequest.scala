package mesosphere.marathon.plugin.http

/**
  * Simple abstraction for a HTTP Request.
  */
trait HttpRequest {

  /**
    * Returns the name of the HTTP method with which this request was made.
    * For example, GET, POST, or PUT.
    */
  def method: String

  /**
    * Get the header values for given header name
    * @param name the name of the header
    * @return the seq of values
    */
  def header(name: String): Seq[String]

  /**
    * Get the value of the cookie with a given name.
    * @param name the name of the cookie.
    * @return Some(value) if the cookie has this value or None otherwise.
    */
  def cookie(name: String): Option[String]

  /**
    * The path of that request.
    * @return the request path.
    */
  def requestPath: String

  /**
    * Get the values of a given request parameter.
    * @param name the name of the query parameter.
    * @return the values of the query parameter.
    */
  def queryParam(name: String): Seq[String]

  /**
    * @return IP address of the client that sent the request
    */
  def remoteAddr: String
}

