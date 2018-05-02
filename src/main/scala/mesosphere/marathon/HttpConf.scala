package mesosphere.marathon

import org.rogach.scallop.ScallopConf

trait HttpConf extends ScallopConf {

  lazy val disableHttp = opt[Boolean](
    "disable_http",
    descr = "Disable listening for HTTP requests completely. HTTPS is unaffected.",
    noshort = true,
    default = Some(false))

  lazy val httpAddress = opt[String](
    "http_address",
    descr = "The address to listen on for HTTP requests", default = None,
    noshort = true)

  lazy val httpPort = opt[Int](
    "http_port",
    descr = "The port to listen on for HTTP requests", default = Some(8080),
    noshort = true)

  lazy val httpsAddress = opt[String](
    "https_address",
    descr = "The address to listen on for HTTPS requests.", default = None,
    noshort = true)

  lazy val httpsPort = opt[Int](
    "https_port",
    descr = "The port to listen on for HTTPS requests", default = Some(8443),
    noshort = true)

  lazy val sslKeystorePath = opt[String](
    "ssl_keystore_path",
    descr = "Path to the SSL keystore. HTTPS (SSL) " +
      "will be enabled if this option is supplied. Requires `--ssl_keystore_password`. " +
      "May also be specified with the `MESOSPHERE_KEYSTORE_PATH` environment variable.",
    default = sslKeystorePathEnvValue,
    noshort = true
  )

  lazy val sslKeystorePassword = opt[String](
    "ssl_keystore_password",
    descr = "Password for the keystore " +
      "supplied with the `ssl_keystore_path` option. Required if `ssl_keystore_path` is supplied. " +
      "May also be specified with the `MESOSPHERE_KEYSTORE_PASS` environment variable.",
    default = sslKeystorePasswordEnvValue,
    noshort = true
  )

  lazy val httpCredentials = opt[String](
    "http_credentials",
    descr = "Credentials for accessing the http service. " +
      "If empty, anyone can access the HTTP endpoint. A username:password " +
      "pair is expected where the username must not contain ':'. " +
      "May also be specified with the `MESOSPHERE_HTTP_CREDENTIALS` environment variable. ",
    default = httpCredentialsEnvValue,
    noshort = true
  )

  lazy val httpCredentialsRealm = opt[String](
    "http_realm",
    descr = "The security realm (aka 'area') associated with the credentials",
    default = Option("Mesosphere"),
    noshort = true
  )

  lazy val httpCompression = toggle(
    "http_compression",
    default = Some(true),
    noshort = true,
    descrYes = "(Default) Enable http compression.",
    descrNo = "Disable http compression. ",
    prefix = "disable_"
  )

  @deprecated("Asset path is not supported.", since = "0.8.5")
  lazy val assetsFileSystemPath = opt[String](
    "assets_path",
    descr = "Set a local file system path to load assets from, " +
      "instead of loading them from the packaged jar.",
    default = None, noshort = true, hidden = true)

  lazy val httpCredentialsEnvValue: Option[String] = sys.env.get(HttpConf.httpCredentialsEnvName)
  lazy val sslKeystorePathEnvValue: Option[String] = sys.env.get(HttpConf.sslKeystorePathEnvName)
  lazy val sslKeystorePasswordEnvValue: Option[String] = sys.env.get(HttpConf.sslKeystorePasswordEnvName)

}

object HttpConf {
  val httpCredentialsEnvName: String = "MESOSPHERE_HTTP_CREDENTIALS"
  val sslKeystorePathEnvName: String = "MESOSPHERE_KEYSTORE_PATH"
  val sslKeystorePasswordEnvName: String = "MESOSPHERE_KEYSTORE_PASS"
}
