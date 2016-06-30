package mesosphere.marathon.integration.setup

import java.io.File
import java.net.URL
import javax.net.ssl.{ HttpsURLConnection, SSLContext }

import mesosphere.marathon.io.SSLContextUtil

/**
  * Create SSL context for tests.
  */
object SSLContextTestUtil {
  val keyStorePassword = "password"

  lazy val selfSignedKeyStoreURL = Option(getClass.getResource("/test-keystore.jks").getFile).getOrElse(
    throw new RuntimeException(s"Could not find resource /test-keystore.jks")
  )
  lazy val selfSignedKeyStorePath = new File(selfSignedKeyStoreURL).getAbsolutePath
  lazy val selfSignedSSLContext = SSLContextUtil.createSSLContext(
    Some(selfSignedKeyStorePath),
    Some(keyStorePassword)
  )

  lazy val caTrustStoreURL = Option(getClass.getResource("/ca-truststore.jks")).getOrElse(
    throw new RuntimeException(s"Could not find resource /ca-truststore.jks")
  )
  lazy val caTrustStorePath = caTrustStoreURL.getPath
  lazy val caKeyStoreURL = Option(getClass.getResource("/ca-keystore.jks")).getOrElse(
    throw new RuntimeException(s"Could not find resource /ca-keystore.jks")
  )
  lazy val caKeyStorePath = new File(caKeyStoreURL.getPath).getAbsolutePath
  lazy val caSignedSSLContext: SSLContext = SSLContextUtil.createSSLContext(
    Some(caKeyStorePath),
    Some(keyStorePassword)
  )

  def sslConnection(url: URL, sslContext: SSLContext): HttpsURLConnection = {
    val connection = url.openConnection().asInstanceOf[HttpsURLConnection]
    connection.setSSLSocketFactory(sslContext.getSocketFactory)
    connection
  }
}
