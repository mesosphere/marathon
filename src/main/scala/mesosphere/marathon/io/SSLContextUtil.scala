package mesosphere.marathon.io

import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.{ TrustManagerFactory, SSLContext }

/**
  * Util for create SSLContext objects.
  */
object SSLContextUtil {

  //scalastyle:off null

  /**
    * Create an SSLContext which accepts the certificates in the given key store (if any).
    */
  def createSSLContext(keyStoreOpt: Option[String], passwordOpt: Option[String]): SSLContext = keyStoreOpt match {
    case Some(keystorePath) => createSSLContext(keystorePath, passwordOpt)
    case None               => SSLContext.getDefault
  }

  private[this] def createSSLContext(keyStorePath: String, passwordOpt: Option[String]): SSLContext = {
    // load keystore from specified cert store (or default)
    val ts = KeyStore.getInstance(KeyStore.getDefaultType)
    IO.using(new FileInputStream(keyStorePath)) { in =>
      ts.load(in, passwordOpt.map(_.toCharArray).orNull)
    }

    // initialize a new TMF with the ts we just loaded
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ts)

    // acquire X509 trust manager from factory
    val context = SSLContext.getInstance("TLS")
    context.init( /* no key managers */ null, tmf.getTrustManagers, /* no secure random */ null)
    context
  }
}
