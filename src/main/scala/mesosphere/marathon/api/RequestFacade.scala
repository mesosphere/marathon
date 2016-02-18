package mesosphere.marathon.api

import javax.servlet.http.HttpServletRequest

import mesosphere.marathon.plugin.http.HttpRequest

import scala.collection.JavaConverters._

class RequestFacade(request: HttpServletRequest, path: String) extends HttpRequest {
  def this(request: HttpServletRequest) = this(request, request.getRequestURI)
  // Jersey will not allow calls to the request object from another thread
  // To circumvent that, we have to copy all data during creation
  val headers = request.getHeaderNames.asScala.map(header => header -> request.getHeaders(header).asScala.toSeq).toMap
  val cookies = request.getCookies
  val params = request.getParameterMap
  val remoteAddr = request.getRemoteAddr
  override def header(name: String): Seq[String] = headers.getOrElse(name, Seq.empty)
  override def requestPath: String = path
  override def cookie(name: String): Option[String] = cookies.find(_.getName == name).map(_.getValue)
  override def queryParam(name: String): Seq[String] = params.asScala.get(name).map(_.toSeq).getOrElse(Seq.empty)
  override def method: String = request.getMethod
}
