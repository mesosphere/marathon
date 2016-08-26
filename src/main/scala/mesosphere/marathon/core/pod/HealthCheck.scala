package mesosphere.marathon.core.pod

sealed trait HealthCheck
case class HttpHealthCheck(endpoint: String, path: Option[String], scheme: HttpScheme) extends HealthCheck
case class TcpHealthCheck(endpoint: String) extends HealthCheck
case class CommandHealthCheck(command: Command)

sealed trait HttpScheme
case object HTTP extends HttpScheme
case object HTTPS extends HttpScheme
