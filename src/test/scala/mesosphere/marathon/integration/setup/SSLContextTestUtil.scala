package mesosphere.marathon.integration.setup

import java.net.URL
import javax.net.ssl.HttpsURLConnection

import mesosphere.marathon.io.SSLContextUtil

/**
  * Create SSL context for tests.
  */
object SSLContextTestUtil {
  val keyStorePassword = "password"

  lazy val keyStoreURL = Option(getClass.getResource("/test-keystore.jks")).getOrElse(
    throw new RuntimeException(s"Could not find resource /test-keystore.jks")
  )
  lazy val keyStorePath = keyStoreURL.getPath

  lazy val testSSLContext = SSLContextUtil.createSSLContext(Some(keyStorePath), Some(keyStorePassword))

  def sslConnection(url: URL): HttpsURLConnection = {
    val connection = url.openConnection().asInstanceOf[HttpsURLConnection]
    connection.setSSLSocketFactory(SSLContextTestUtil.testSSLContext.getSocketFactory)
    connection
  }
}
