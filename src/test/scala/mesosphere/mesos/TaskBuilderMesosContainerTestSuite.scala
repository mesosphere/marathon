package mesosphere.mesos

import mesosphere.marathon.state.PathId._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.{ Resource, _ }
import mesosphere.marathon.state.{ AppDefinition, Container, _ }
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderMesosContainerTestSuite extends TaskBuilderSuiteBase {

  import mesosphere.mesos.protos.Implicits._

  "TaskBuilder" when {

    "given an offer and an app definition with a MESOS container using named, external [ExternalVolume] volumes" should {

      import mesosphere.marathon.core.externalvolume.impl.providers.DVDIProviderVolumeToUnifiedMesosVolumeTest._

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "/product/frontend".toPath,
          cmd = Some("foo"),
          cpus = 1.0,
          mem = 32.0,
          executor = "/qazwsx",
          portDefinitions = Nil,
          container = Some(Container.Mesos(
            volumes = Seq[Volume](
              ExternalVolume("/container/path", ExternalVolumeInfo(
                name = "namedFoo",
                provider = "dvdi",
                options = Map[String, String]("dvdi/driver" -> "bar")
              ), MesosProtos.Volume.Mode.RW),
              ExternalVolume("/container/path2", ExternalVolumeInfo(
                size = Some(2L),
                name = "namedEdc",
                provider = "dvdi",
                options = Map[String, String]("dvdi/driver" -> "ert")
              ), MesosProtos.Volume.Mode.RW)
            )
          ))
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val Some((taskInfo, _)) = task
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

      // sanity, we DID match the offer, right?
      "set the cpu resource" in {
        resource("cpus") should be(ScalarResource("cpus", 1))
      }
      "not have a container" in {
        taskInfo.hasContainer should be(false)
      }
      "not have a command" in {
        taskInfo.hasCommand should be(false)
      }
      "have an executor with a MESOS container" in {
        taskInfo.getExecutor.hasContainer should be(true)
        taskInfo.getExecutor.getContainer.hasMesos should be(true)
      }
      "have a MESOS container with volumes" in {
        // check protobuf construction, should be a ContainerInfo w/ no volumes, w/ envvar
        taskInfo.getExecutor.getContainer.getVolumesList.size should be(2) withClue (s"check that container has 2 volumes declared, got instead ${taskInfo.getExecutor.getContainer.getVolumesList}")
      }

      val got1 = taskInfo.getExecutor.getContainer.getVolumes(0)
      "set container path for first volume" in { got1.getContainerPath should be("/container/path") }
      "set volume mode for first volume" in { got1.getMode should be(MesosProtos.Volume.Mode.RW) }
      "set docker volume name for first volume" in { got1.getSource.getDockerVolume.getName should be("namedFoo") }
      "set docker volume driver for first volume" in { got1.getSource.getDockerVolume.getDriver should be("bar") }

      val got2 = taskInfo.getExecutor.getContainer.getVolumes(1)
      "set container path for second volume" in { got2.getContainerPath should be("/container/path2") }
      "set volume mode for second volume" in { got2.getMode should be(MesosProtos.Volume.Mode.RW) }
      "set docker volume name for second volume" in { got2.getSource.getDockerVolume.getName should be("namedEdc") }
      "set docker volume driver for second volume" in { got2.getSource.getDockerVolume.getDriver should be("ert") }
      "set docker volume options" in {
        got2.getSource.getDockerVolume.getDriverOptions.getParameter(0).getKey should be("size")
        got2.getSource.getDockerVolume.getDriverOptions.getParameter(0).getValue should be("2")
      }
    }

    "given an offer and an app definition with MESOS Docker container" should {

      val offer = MarathonTestHelper.makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved)
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          container = Some(Container.MesosDocker(
            image = "busybox",
            credential = Some(Container.Credential(
              principal = "aPrincipal",
              secret = Some("aSecret")
            ))
          )),
          portDefinitions = Seq.empty,
          ipAddress = Some(IpAddress(networkName = Some("vnet")))
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val (taskInfo, _) = task.get

      "define a task" in { task should be('defined) }
      "set a container" in { taskInfo.hasContainer should be(true) }
      "set container type to MESOS" in { taskInfo.getContainer.getType should be (MesosProtos.ContainerInfo.Type.MESOS) }
      "set a mesos container" in { taskInfo.getContainer.hasMesos should be (true) }
      "set a mesos container image" in { taskInfo.getContainer.getMesos.hasImage should be (true) }
      "set the mesos container image type to docker" in { taskInfo.getContainer.getMesos.getImage.getType should be (MesosProtos.Image.Type.DOCKER) }
      "set the image docker" in { taskInfo.getContainer.getMesos.getImage.hasDocker should be (true) }
      "set image docker credentials" in {
        taskInfo.getContainer.getMesos.getImage.getDocker.hasCredential should be(true)
        taskInfo.getContainer.getMesos.getImage.getDocker.getCredential.getPrincipal should be("aPrincipal")
        taskInfo.getContainer.getMesos.getImage.getDocker.getCredential.hasSecret should be(true)
        taskInfo.getContainer.getMesos.getImage.getDocker.getCredential.getSecret should be("aSecret")
      }
    }

    "given an offer and an app definition with a MESOS AppC container" should {

      val offer = MarathonTestHelper.makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved)
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          container = Some(Container.MesosAppC(
            image = "anImage",
            id = Some("sha512-aHashValue"),
            labels = Map("foo" -> "bar", "test" -> "test")
          )),
          portDefinitions = Seq.empty,
          ipAddress = Some(IpAddress(networkName = Some("vnet")))
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val (taskInfo, _) = task.get

      "define a task" in { task should be('defined) }
      "set a container" in { taskInfo.hasContainer should be(true) }
      "set container type to MESOS" in { taskInfo.getContainer.getType should be (MesosProtos.ContainerInfo.Type.MESOS) }
      "set a mesos container" in { taskInfo.getContainer.hasMesos should be (true) }
      "set a mesos container image" in { taskInfo.getContainer.getMesos.hasImage should be (true) }
      "set the mesos container image type to APPC" in { taskInfo.getContainer.getMesos.getImage.getType should be (MesosProtos.Image.Type.APPC) }
      "set the image APPC" in { taskInfo.getContainer.getMesos.getImage.hasAppc should be (true) }
      "set the AppC id" in {
        taskInfo.getContainer.getMesos.getImage.getAppc.hasId should be(true)
        taskInfo.getContainer.getMesos.getImage.getAppc.getId should be("sha512-aHashValue")
      }
      "set the Appc labels" in {
        taskInfo.getContainer.getMesos.getImage.getAppc.hasLabels should be(true)
        taskInfo.getContainer.getMesos.getImage.getAppc.getLabels.getLabels(0).getKey should be("foo")
        taskInfo.getContainer.getMesos.getImage.getAppc.getLabels.getLabels(0).getValue should be("bar")
        taskInfo.getContainer.getMesos.getImage.getAppc.getLabels.getLabels(1).getKey should be("test")
        taskInfo.getContainer.getMesos.getImage.getAppc.getLabels.getLabels(1).getValue should be("test")
      }
    }
  }
}
