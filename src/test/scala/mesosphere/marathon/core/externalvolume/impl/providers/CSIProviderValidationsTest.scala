package mesosphere.marathon.core.externalvolume.impl.providers

import mesosphere.UnitTest

class CSIProviderValidationsTest extends UnitTest {
  "prevent app from scaling up for non-multi access modes" in {
    // TODO - implement me
  }

  "prevent an app from being specified multiple times for non-multi access modes" in {
    // TODO - implement me
  }

  "prevent a CSI volume from having a RW volume mount mode if the app is read-only" in {
    // TODO - implement me
  }

  "prevent a CSI volume from scaling if multi-reader-single-writer and mount mode RW" in {
    // TODO - implement me
  }

}
