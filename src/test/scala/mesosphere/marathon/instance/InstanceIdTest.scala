package mesosphere.marathon
package instance

import java.util.UUID

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.{PrefixInstance, PrefixMarathon}
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId._
import org.scalatest.Inside

class InstanceIdTest extends UnitTest with Inside {

  "InstanceId" should {
    "AppIds can be converted to InstanceIds and back to AppIds" in {
      val appId = "/test/foo/bla/rest".toPath
      val instanceId = Instance.Id.forRunSpec(appId)
      instanceId.runSpecId should equal(appId)
    }

    "be converted to TaskIds without container name" in {
      val appId = "/test/foo/bla/rest".toPath
      val instanceId = Instance.Id.forRunSpec(appId)
      val taskId = Task.Id(instanceId)
      taskId.idString should be(instanceId.idString + "._app.1")
    }

    "be converted to TaskIds with container name" in {
      val appId = "/test/foo/bla/rest".toPath
      val instanceId = Instance.Id.forRunSpec(appId)
      val container = MesosContainer("firstOne", resources = Resources())
      val taskId = Task.Id(instanceId, Some(container))
      taskId.idString should be(instanceId.idString + ".firstOne.1")
    }

    "be converted from TaskIds with container name" in {
      val appId = "/test/foo/bla/rest".toPath
      val uuid = UUID.fromString("b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6")
      val parsedTaskId = Task.Id.parse("test_foo_bla_rest.instance-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6.someContainerName")
      parsedTaskId.runSpecId should be(appId)
      parsedTaskId.instanceId should be(Instance.Id(appId, PrefixInstance, uuid))
      inside(parsedTaskId) {
        case Task.EphemeralTaskId(_, containerName) =>
          containerName should be('nonEmpty)
          containerName should be(Some("someContainerName"))
      }
    }

    "be converted from TaskIds without a container name" in {
      val appId = "/test/foo/bla/rest".toPath
      val uuid = UUID.fromString("b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6")
      val parsedTaskId = Task.Id.parse("test_foo_bla_rest.instance-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6._app")
      parsedTaskId.runSpecId should be(appId)
      parsedTaskId.instanceId should be(Instance.Id(appId, PrefixInstance, uuid))
      inside(parsedTaskId) {
        case Task.EphemeralTaskId(_, containerName) =>
          containerName should be('empty)
      }
    }

    "be created by static string" in {
      val idString = "app.marathon-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6"
      val uuid = UUID.fromString("b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6")
      val instanceId = Instance.Id("/app".toPath, PrefixMarathon, uuid)
      instanceId.idString should be(idString)
      instanceId.runSpecId.safePath should be("app")
      val taskId = Task.Id.parse(idString + ".app")
      taskId.instanceId should be(instanceId)
    }
  }
}
