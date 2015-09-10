package mesosphere.marathon.interface.http

/**
  * Abstraction for HTTP Response.
  */
trait HttpResponse {

  /**
    * Set header to a specific value
    * @param header the header name
    * @param value the value of the header.
    */
  def header(header: String, value: String)

  /**
    * The status code to send
    * @param code the status code.
    */
  def status(code: Int)

  /**
    * The redirect to send.
    * @param url the url to redirect.
    */
  def sendRedirect(url: String)

  /**
    * Set new cookie with name to a value.
    * @param name the name of the cookie.
    * @param value the value of the cookie.
    */
  def cookie(name: String, value: String)
}
