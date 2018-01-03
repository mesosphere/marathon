trait LogFormat {
  def matches(s: String): Boolean
  val codec: String
  val unframe: String
}

object DcosLogFormat extends LogFormat {
  val regexPrefix = "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}:".r
  val regex = s"${regexPrefix} \\[".r

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
