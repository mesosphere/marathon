package mesosphere.mesos

import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.{ Resource, _ }
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderDockerContainerTestSuite extends TaskBuilderSuiteBase {

  import mesosphere.mesos.protos.Implicits._

  "TaskBuilder" when {

    "given an offer and an app definition with a DOCKER container and relative host path" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 32.0,
          executor = "//cmd",
          portDefinitions = Nil,
          container = Some(Docker(
            volumes = Seq[Volume](
              DockerVolume("/container/path", "relativeDirName", MesosProtos.Volume.Mode.RW)
            )
          ))
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val Some((taskInfo, _)) = task
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

      // check protobuf construction, should be a ContainerInfo w/ volumes
      def vol(path: String): Option[MesosProtos.Volume] = {
        if (taskInfo.hasContainer) {
          taskInfo.getContainer.getVolumesList.asScala.find(_.getHostPath == path)
        } else None
      }

      // sanity, we DID match the offer, right?
      "set the cpu resource" in {
        resource("cpus") should be(ScalarResource("cpus", 1))
      }

      "declare volumes for the container" in {
        assert(taskInfo.getContainer.getVolumesList.size > 0)
      }
      "define a relative directory name" in {
        vol("relativeDirName") should be('defined) withClue (s"missing expected volume relativeDirName, got instead: ${taskInfo.getContainer.getVolumesList}")
      }
    }

    "given an offer and an app definition with a DOCKER container using host-local and external [DockerVolume] volumes" should {
      import mesosphere.marathon.core.externalvolume.impl.providers.DVDIProviderVolumeToUnifiedMesosVolumeTest._

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 32.0,
          executor = "//cmd",
          portDefinitions = Nil,
          container = Some(Docker(
            volumes = Seq[Volume](
              ExternalVolume("/container/path", ExternalVolumeInfo(
                name = "namedFoo",
                provider = "dvdi",
                options = Map[String, String]("dvdi/driver" -> "bar")
              ), MesosProtos.Volume.Mode.RW),
              DockerVolume("/container/path", "relativeDirName", MesosProtos.Volume.Mode.RW)
            )
          ))
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val Some((taskInfo, _)) = task
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

      // sanity, we DID match the offer, right?
      "set the cpu resource" in { resource("cpus") should be(ScalarResource("cpus", 1)) }

      // check protobuf construction, should be a ContainerInfo w/ volumes
      def vol(name: String): Option[MesosProtos.Volume] = {
        if (taskInfo.hasContainer) {
          taskInfo.getContainer.getVolumesList.asScala.find(_.getHostPath == name)
        } else None
      }

      "declare container volumes" in { taskInfo.getContainer.getVolumesList.size should be(2) }

      val got1 = taskInfo.getContainer.getVolumes(0)
      "set container path" in { got1.getContainerPath should be("/container/path") }
      "set volume mode" in { got1.getMode should be(MesosProtos.Volume.Mode.RW) }
      "set docker volume name" in { got1.getSource.getDockerVolume.getName should be("namedFoo") }
      "set docker volume driver" in { got1.getSource.getDockerVolume.getDriver should be("bar") }

      "define the relative directory name" in {
        vol("relativeDirName") should be('defined) withClue (s"missing expected volume relativeDirName, got instead: ${taskInfo.getContainer.getVolumesList}")
      }
    }

    "given an offer and an app definition with a DOCKER container using external [DockerVolume] volumes" should {
      import mesosphere.marathon.core.externalvolume.impl.providers.DVDIProviderVolumeToUnifiedMesosVolumeTest._

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 32.0,
          executor = "//cmd",
          portDefinitions = Nil,
          container = Some(Docker(
            volumes = Seq[Volume](
              ExternalVolume("/container/path", ExternalVolumeInfo(
                name = "namedFoo",
                provider = "dvdi",
                options = Map[String, String]("dvdi/driver" -> "bar")
              ), MesosProtos.Volume.Mode.RW),
              ExternalVolume("/container/path2", ExternalVolumeInfo(
                name = "namedEdc",
                provider = "dvdi",
                options = Map[String, String]("dvdi/driver" -> "ert", "dvdi/boo" -> "baa")
              ), MesosProtos.Volume.Mode.RO)
            )
          ))
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val Some((taskInfo, _)) = task
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

      // sanity, we DID match the offer, right?
      "set the cpu resource" in { resource("cpus") should be(ScalarResource("cpus", 1)) }

      "declare container volumes" in {
        taskInfo.getContainer.getVolumesList.size should be(2) withClue (s"check that container has 2 volumes declared, got instead ${taskInfo.getExecutor.getContainer.getVolumesList}")
      }

      val got1 = taskInfo.getContainer.getVolumes(0)
      "set container path for first volume" in { got1.getContainerPath should be("/container/path") }
      "set volume mode for first volume" in { got1.getMode should be(MesosProtos.Volume.Mode.RW) }
      "set docker volume name for first volume" in { got1.getSource.getDockerVolume.getName should be("namedFoo") }
      "set docker volume driver for first volume" in { got1.getSource.getDockerVolume.getDriver should be("bar") }

      val got2 = taskInfo.getContainer.getVolumes(1)
      "set container path for second volume" in { got2.getContainerPath should be("/container/path2") }
      "set volume mode for second volume" in { got2.getMode should be(MesosProtos.Volume.Mode.RO) }
      "set docker volume name for second volume" in { got2.getSource.getDockerVolume.getName should be("namedEdc") }
      "set docker volume driver for second volume" in { got2.getSource.getDockerVolume.getDriver should be("ert") }
      "set docker volume options" in {
        got2.getSource.getDockerVolume.getDriverOptions.getParameter(0).getKey should be("boo")
        got2.getSource.getDockerVolume.getDriverOptions.getParameter(0).getValue should be("baa")
      }
    }
  }
}
