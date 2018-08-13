package mesosphere.marathon
package integration

import org.scalatest.Sequential

/**
  * Wrapper that prohibits parallel test run of the respective test suites.
  */
class SequentialIntegrationTests extends Sequential(
  new MesosAppIntegrationTest,
  new UpgradeIntegrationTest
)