package mesosphere.marathon.integration.setup

import java.io.File

import org.apache.commons.io.FileUtils
import org.scalatest.{ BeforeAndAfterAllConfigMap, ConfigMap }

trait StartedZookeeper extends BeforeAndAfterAllConfigMap { self: IntegrationFunSuite =>

  private var configOption: Option[IntegrationTestConfig] = None
  def config: IntegrationTestConfig = configOption.get

  override protected def beforeAll(configMap: ConfigMap): Unit = {
    super.beforeAll(configMap)
    configOption = Some(IntegrationTestConfig(configMap))
    if (!config.useExternalSetup) {
      FileUtils.deleteDirectory(new File("/tmp/foo/mesos"))
      ProcessKeeper.startZooKeeper(config.zkPort, "/tmp/foo/mesos")
    }
  }

  override protected def afterAll(configMap: ConfigMap): Unit = {
    super.afterAll(configMap)
    ProcessKeeper.shutdown()
  }
}
