package mesosphere.marathon
package plugin.http

/**
  * Abstraction for HTTP Response.
  */
trait HttpResponse {

  /**
    * Set header to a specific value
    * @param header the header name
    * @param value the value of the header.
    */
  def header(header: String, value: String): Unit

  /**
    * The status code to send
    * @param code the status code.
    */
  def status(code: Int): Unit

  /**
    * The redirect to send.
    * @param url the url to redirect.
    */
  def sendRedirect(url: String): Unit

  /**
    * Send cookie with name to a value.
    * @param name the name of the cookie.
    * @param value the value of the cookie.
    * @param maxAge the maximum age of this cookie until it expires
    * @param secure if this is a secure cookie
    */
  def cookie(name: String, value: String, maxAge: Int, secure: Boolean): Unit

  /**
    * Set the body of the response to send.
    * @param mediaType the media type of the response.
    * @param bytes the body as byte array.
    */
  def body(mediaType: String, bytes: Array[Byte]): Unit
}
