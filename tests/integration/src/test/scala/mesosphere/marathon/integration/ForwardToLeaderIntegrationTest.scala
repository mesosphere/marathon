package mesosphere.marathon
package integration

import java.net.URL

import com.mesosphere.utils.PortAllocator
import mesosphere.marathon.api.forwarder.RequestForwarder
import org.apache.commons.io.IOUtils
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.api.LeaderProxyFilter
import mesosphere.marathon.integration.facades.AppMockFacade
import mesosphere.marathon.integration.setup._
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.time.{Milliseconds, Seconds, Span}
import play.api.libs.json.{JsObject, JsString}

/**
  * Tests forwarding requests.
  */
class ForwardToLeaderIntegrationTest extends AkkaIntegrationTest with TableDrivenPropertyChecks {

  val forwarderStartTimeout = PatienceConfiguration.Timeout(Span(60, Seconds))
  val forwarderStartInterval = PatienceConfiguration.Interval(Span(100, Milliseconds))

  def withForwarder[T](testCode: ForwarderService => T): T = {
    val forwarder = new ForwarderService()
    try {
      testCode(forwarder)
    } finally {
      forwarder.close()
    }
  }

  "ForwardingToLeader" should {
    "direct ping" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp()
      helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val appFacade = new AppMockFacade("localhost", helloApp.port)
      val result = appFacade.ping().futureValue
      result should be(OK)
      result.entityString should be("pong\n")
      result.value.headers.exists(_.name == RequestForwarder.HEADER_VIA) should be(false)
      result.value.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) should be(1)
      result.value.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value should be(
        s"http://localhost:${helloApp.port}"
      )
    }

    "forward ping" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp()
      helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val forwardApp = forwarder.startForwarder(helloApp.port)
      forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

      val appFacade = new AppMockFacade("localhost", forwardApp.port)
      val result = appFacade.ping().futureValue
      result should be(OK)
      result.entityString should be("pong\n")
      result.value.headers.count(_.name == RequestForwarder.HEADER_VIA) should be(1)
      result.value.headers.find(_.name == RequestForwarder.HEADER_VIA).get.value should be(s"1.1 localhost:${forwardApp.port}")
      result.value.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) should be(1)
      result.value.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value should be(
        s"http://localhost:${helloApp.port}"
      )
    }

    "direct HTTPS ping" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp(
        "--https_port",
        Seq(
          "--disable_http",
          "--ssl_keystore_path",
          SSLContextTestUtil.selfSignedKeyStorePath,
          "--ssl_keystore_password",
          SSLContextTestUtil.keyStorePassword,
          "--https_address",
          "localhost"
        )
      )
      helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"

      val pingURL = new URL(s"https://localhost:${helloApp.port}/ping")
      val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.selfSignedSSLContext)
      val via = connection.getHeaderField(RequestForwarder.HEADER_VIA)
      val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
      val response = IOUtils.toString(connection.getInputStream, "UTF-8")
      response should be("pong\n")
      via should be(null)
      leader should be(s"https://localhost:${helloApp.port}")
    }

    "forwarding HTTPS ping with a self-signed cert" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp(
        "--https_port",
        Seq(
          "--disable_http",
          "--ssl_keystore_path",
          SSLContextTestUtil.selfSignedKeyStorePath,
          "--ssl_keystore_password",
          SSLContextTestUtil.keyStorePassword,
          "--https_address",
          "localhost"
        )
      )
      helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"

      val forwardApp = forwarder.startForwarder(
        helloApp.port,
        "--https_port",
        args = Seq(
          "--disable_http",
          "--ssl_keystore_path",
          SSLContextTestUtil.selfSignedKeyStorePath,
          "--ssl_keystore_password",
          SSLContextTestUtil.keyStorePassword,
          "--https_address",
          "localhost"
        )
      )
      forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

      val pingURL = new URL(s"https://localhost:${forwardApp.port}/ping")
      val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.selfSignedSSLContext)
      val via = connection.getHeaderField(RequestForwarder.HEADER_VIA)
      val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
      val response = IOUtils.toString(connection.getInputStream, "UTF-8")
      response should be("pong\n")
      via should be(s"1.1 localhost:${forwardApp.port}")
      leader should be(s"https://localhost:${helloApp.port}")
    }

    "forwarding HTTPS ping with a ca signed cert" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp(
        "--https_port",
        Seq(
          "--disable_http",
          "--ssl_keystore_path",
          SSLContextTestUtil.caKeyStorePath,
          "--ssl_keystore_password",
          SSLContextTestUtil.keyStorePassword,
          "--https_address",
          "localhost"
        )
      )
      helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"

      val forwardApp = forwarder.startForwarder(
        helloApp.port,
        "--https_port",
        args = Seq(
          "--disable_http",
          "--ssl_keystore_path",
          SSLContextTestUtil.caKeyStorePath,
          "--ssl_keystore_password",
          SSLContextTestUtil.keyStorePassword,
          "--https_address",
          "localhost"
        )
      )

      forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
      val forwardPort = forwardApp.port

      val pingURL = new URL(s"https://localhost:${forwardPort}/ping")
      val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.caSignedSSLContext)
      val via = connection.getHeaderField(RequestForwarder.HEADER_VIA)
      val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
      val response = IOUtils.toString(connection.getInputStream, "UTF-8")
      response should be("pong\n")
      via should be(s"1.1 localhost:$forwardPort")
      leader should be(s"https://localhost:${helloApp.port}")
    }

    "direct 404" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp()
      helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val appFacade = new AppMockFacade("localhost", helloApp.port)
      val result = appFacade.get("/notfound", assertResult = false).futureValue
      result should be(NotFound)
    }

    "forwarding 404" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp()
      helloApp.launched.futureValue
      val forwardApp = forwarder.startForwarder(helloApp.port)
      forwardApp.launched.futureValue
      val appFacade = new AppMockFacade("localhost", forwardApp.port)
      val result = appFacade.get("/notfound", assertResult = false).futureValue
      result should be(NotFound)
    }

    "direct internal server error" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp()
      helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val appFacade = new AppMockFacade("localhost", helloApp.port)
      val result = appFacade.get("/hello/crash", assertResult = false).futureValue
      result should be(ServerError)
      result.entityString should be("Error")
    }

    "forwarding internal server error" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp()
      helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val forwardApp = forwarder.startForwarder(helloApp.port)
      forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
      val appFacade = new AppMockFacade("localhost", forwardApp.port)
      val result = appFacade.get("/hello/crash", assertResult = false).futureValue
      result should be(ServerError)
      result.entityString should be("Error")
    }

    "forwarding connection failed" in withForwarder { forwarder =>
      val forwardApp = forwarder.startForwarder(PortAllocator.ephemeralPort())
      forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
      val appFacade = new AppMockFacade("localhost", forwardApp.port)
      val result = appFacade.ping(assertResult = false).futureValue
      result should be(BadGateway)
    }

    "forwarding loop" in withForwarder { forwarder =>
      val forwardApp1 = forwarder.startForwarder(PortAllocator.ephemeralPort())
      forwardApp1.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
      val forwardApp2 = forwarder.startForwarder(PortAllocator.ephemeralPort())
      forwardApp2.launched.futureValue

      val appFacade = new AppMockFacade("localhost", forwardApp1.port)
      val result = appFacade.ping(assertResult = false).futureValue
      result should be(BadGateway)
    }

    "forwarding a POST request with no Content-Type header set" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp()
      helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val forwardApp = forwarder.startForwarder(helloApp.port)
      forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

      val appFacade = new AppMockFacade("localhost", forwardApp.port)
      val result = appFacade.post("/headers").futureValue

      result should be(OK)

      result.value.headers.count(_.name == RequestForwarder.HEADER_VIA) should be(1)
      result.value.headers.find(_.name == RequestForwarder.HEADER_VIA).get.value should be(s"1.1 localhost:${forwardApp.port}")
      result.value.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) should be(1)
      result.value.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value should be(
        s"http://localhost:${helloApp.port}"
      )

      val json = result.entityJson.asInstanceOf[JsObject]
      (json \ "Content-Type").toOption.map(_.asInstanceOf[JsString].value) shouldEqual None
    }

    "redirect a request to /v2/events to a leader" in withForwarder { forwarder =>
      val helloApp = forwarder.startHelloApp()
      helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val forwardApp = forwarder.startForwarder(helloApp.port)
      forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

      val leaderResult = (new AppMockFacade("localhost", helloApp.port)).get("/v2/events").futureValue
      val forwardResult = (new AppMockFacade("localhost", forwardApp.port)).get("/v2/events", assertResult = false).futureValue

      leaderResult should be(OK)
      leaderResult.entityString should be("events")

      forwardResult should be(Redirect)
    }

  }
}
