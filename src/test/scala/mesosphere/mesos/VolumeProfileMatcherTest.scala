package mesosphere.mesos

import mesosphere.UnitTest
import org.apache.mesos

class VolumeProfileMatcherTest extends UnitTest {
  "matchesProfileName" should {
    "match disk with profile if that profile is required" in {
      val disk = diskResource(profile = Some("profile"))
      VolumeProfileMatcher.matchesProfileName(Some("profile"), disk) shouldBe true
    }
    "not match disk with profile if no profile is required" in {
      val disk = diskResource(profile = Some("profile"))
      VolumeProfileMatcher.matchesProfileName(None, disk) shouldBe false
    }
    "match disk without profile when no profile is required" in {
      val disk = diskResource(profile = None)
      VolumeProfileMatcher.matchesProfileName(None, disk) shouldBe true
    }
    "not match disk with profile if a different profile is required" in {
      val disk = diskResource(profile = Some("profile"))
      VolumeProfileMatcher.matchesProfileName(Some("needed-profile"), disk) shouldBe false
    }
  }

  /** Helper to create disk resources with/without profile */
  def diskResource(profile: Option[String]): mesos.Protos.Resource = {
    val source = mesos.Protos.Resource.DiskInfo.Source
      .newBuilder()
      .setType(mesos.Protos.Resource.DiskInfo.Source.Type.PATH)
      .setPath(mesos.Protos.Resource.DiskInfo.Source.Path.newBuilder().setRoot("test"))
      .setId("pathDiskId")
    profile.foreach { p =>
      source.setProfile(p)
      source.setVendor("vendorId")
    }

    mesos.Protos.Resource
      .newBuilder()
      .setType(mesos.Protos.Value.Type.SCALAR)
      .setName("disk")
      .setDisk(
        mesos.Protos.Resource.DiskInfo
          .newBuilder()
          .setSource(source)
      )
      .build()
  }
}
