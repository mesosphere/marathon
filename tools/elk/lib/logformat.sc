trait LogFormat {
  def matches(s: String): Boolean
  val codec: String
  val unframe: String
  /**
    * Example line for log format
    */
  val example: String
}

/**
  * This log format should be used by the majority of DCOS clusters.
  *
  * It will match a log line starts with this:
  *
  *     1971-01-01 00:00:00: [
  *
  * It will configure logstash to join together lines if they lack the `[` at then end.
  *
  *     1971-01-01 00:00:00: [INFO] This is a
  *     1971-01-01 00:00:00: multi-line log message.
  */
object DcosLogFormat extends LogFormat {
  val regexPrefix = "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}:".r
  val regex = s"${regexPrefix} \\[".r

  val example = "1971-01-01 00:00:00: [INFO] {message}"

  def matches(s: String): Boolean =
    regex.findFirstMatchIn(s).nonEmpty

  val codec = s"""|multiline {
                  |  pattern => "${regexPrefix} [^\\[]"
                  |  what => "previous"
                  |  max_lines => 1000
                  |}""".stripMargin

  val unframe = s"""|filter {
                    |  grok {
                    |    match => {
                    |      "message" => "(%{YEAR}-%{MONTHNUM}-%{MONTHDAY} %{TIME}): *%{GREEDYDATA:message}"
                    |    }
                    |    tag_on_failure => []
                    |    overwrite => [ "message" ]
                    |  }
                    |}""".stripMargin

}

object LogFormat {
  val all = List(DcosLogFormat)
}
