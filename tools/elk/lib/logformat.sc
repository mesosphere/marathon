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
object DcosLogFormat extends (String => Option[LogFormat]) {
  val regexPrefix = "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}:".r
  val regex = s"${regexPrefix} \\[".r

  val example = "1971-01-01 00:00:00: [INFO] {message}"

  override def apply(s: String): Option[LogFormat] =
    if (regex.findFirstMatchIn(s).nonEmpty)
      Some(
        LogFormat(
          codec = codec,
          example = example,
          unframe = unframe))
    else
      None

  private val codec = s"""|multiline {
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

object DirectJournalFormat extends (String => Option[LogFormat]) {
  // "Jan 13 17:44:31 hostname.domain marathon[11956]: [2018-01-13 17:44:31,546] INFO  Message"
  // "Jan 13 17:44:27 ip-10-0-6-18.us-west-2.compute.internal marathon[11956]:         continuing line",

  val regexPrefix = "^[a-zA-Z]+ [0-9]+ [0-9:]+ ([^ ]+) [^:]+:".r
  val regex = s"${regexPrefix} \\[".r

  val example = "Jan 13 17:44:31 hostname.domain marathon[11956]: [2018-01-13 17:44:31,546] INFO  Message"

  override def apply(line: String): Option[LogFormat] =
    regex.findFirstMatchIn(line).map { m =>
      LogFormat(
        codec = codec,
        example = example,
        unframe = unframe,
        host = Some(m.subgroups(0)))
    }

  val codec = s"""|multiline {
                  |  pattern => "${regexPrefix} \\["
                  |  negate => "true"
                  |  what => "previous"
                  |  max_lines => 1000
                  |}""".stripMargin

  val unframe = s"""|filter {
                    |  grok {
                    |    match => {
                    |      "message" => "${regexPrefix} *%{GREEDYDATA:message}"
                    |    }
                    |    tag_on_failure => []
                    |    overwrite => [ "message" ]
                    |  }
                    |}""".stripMargin


}

case class LogFormat(
  codec: String,
  example: String,
  unframe: String,
  host: Option[String] = None
)

object LogFormat {
  def tryMatch(line: String): Option[LogFormat] = {
    DcosLogFormat(line)
      .orElse(DirectJournalFormat(line))
  }
}
