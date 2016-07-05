package mesosphere.marathon.io

import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.{ CertificateException, X509Certificate }
import javax.net.ssl.{ SSLContext, TrustManager, TrustManagerFactory, X509TrustManager }

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
    case None => SSLContext.getDefault
  }

  private[this] def createSSLContext(keyStorePath: String, passwordOpt: Option[String]): SSLContext = {
    def getX509TrustManager(tmf: TrustManagerFactory): X509TrustManager = {
      tmf.getTrustManagers.find(_.isInstanceOf[X509TrustManager]).orNull.asInstanceOf[X509TrustManager]
    }

    val keyStoreTrustManager: X509TrustManager = {
      // load keystore from specified cert store (or default)
      val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
      IO.using(new FileInputStream(keyStorePath)) { in =>
        keyStore.load(in, passwordOpt.map(_.toCharArray).orNull)
      }

      // initialize a new TMF with the ts we just loaded
      val selfSignedTMF = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      selfSignedTMF.init(keyStore)

      getX509TrustManager(selfSignedTMF)
    }

    val systemTrustManager: X509TrustManager = {
      val systemTMF = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      systemTMF.init(null.asInstanceOf[KeyStore])
      getX509TrustManager(systemTMF)
    }

    /*
     * Use a custom SSL Trust Manager when proxying requests to the current leader. This TM uses the keys/certs in the
     * key store specified through cmd line flags and the ones in Java's default Trust Store.
     * This should work for self-signed certificates and for certificates signed by a CA in the Trust Store.
     *
     * Inspired by:
     * http://stackoverflow.com/questions/24555890/using-a-custom-truststore-in-java-as-well-as-the-default-one
     */
    val customTrustManager: X509TrustManager = new X509TrustManager() {
      lazy val getAcceptedIssuers = keyStoreTrustManager.getAcceptedIssuers ++ systemTrustManager.getAcceptedIssuers

      override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = {
        try {
          keyStoreTrustManager.checkClientTrusted(chain, authType)
        } catch {
          case e: CertificateException => systemTrustManager.checkClientTrusted(chain, authType)
        }
      }

      override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {
        try {
          keyStoreTrustManager.checkServerTrusted(chain, authType)
        } catch {
          case e: CertificateException => systemTrustManager.checkServerTrusted(chain, authType)
        }
      }
    }

    // acquire X509 trust manager from factory
    val context = SSLContext.getInstance("TLS")
    context.init( /* no key managers */ null, Array[TrustManager](customTrustManager), /* no secure random */ null)
    context
  }
}
