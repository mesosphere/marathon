package mesosphere.marathon.integration.setup

import mesosphere.marathon.integration.facades.MarathonFacade
import org.scalatest.{ ConfigMap, Suite }
import org.slf4j.LoggerFactory

object MarathonClusterIntegrationTest {
  private val log = LoggerFactory.getLogger(getClass)
}

/**
  * Convenient trait to test against a Marathon cluster.
  *
  * The cluster sized is determined by [[IntegrationTestConfig.clusterSize]].
  */
trait MarathonClusterIntegrationTest extends SingleMarathonIntegrationTest { self: Suite =>
  lazy val marathonFacades: Seq[MarathonFacade] = config.marathonUrls.map(url => new MarathonFacade(url, testBasePath))

  override protected def beforeAll(configMap: ConfigMap): Unit = {
    super.beforeAll(configMap)
    val parameters = List("--master", config.master, "--event_subscriber", "http_callback") ++ extraMarathonParameters
    config.marathonPorts.tail.foreach(port => startMarathon(port, parameters: _*))
  }
}
