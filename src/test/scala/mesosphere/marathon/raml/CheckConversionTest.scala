package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.core.check.{MesosCheck, MesosCommandCheck, MesosHttpCheck, MesosTcpCheck, Check => CoreCheck}
import mesosphere.marathon.state.Command

class CheckConversionTest extends UnitTest {

  def convertToProtobufThenToRAML(check: => MesosCheck, raml: => AppCheck): Unit = {
    "convert to protobuf, then to RAML" in {
      val proto2raml = check.toProto.toRaml
      proto2raml should be(raml)
    }
  }

  "A MesosCommandCheck is converted correctly" when {
    "A MesosCommandCheck" should {
      val check = MesosCommandCheck(command = Command("foo"))
      val raml = check.toRaml[AppCheck]

      behave like convertToProtobufThenToRAML(check, raml)

      "convert to a RAML AppCheck" in {
        raml.tcp should be(empty)
        raml.exec should be(defined)
        raml.http should be(empty)
        raml.intervalSeconds should be(check.interval.toSeconds)
        raml.timeoutSeconds should be(check.timeout.toSeconds)
        raml.delaySeconds should be(check.delay.toSeconds)
      }
    }
    "An AppCheck" should {
      "convert to a MesosCommandCheck" in {
        val check = AppCheck(exec = Some(CommandCheck(ShellCommand("foo"))))
        val core = Some(check.fromRaml).collect {
          case c: MesosCommandCheck => c
        }.getOrElse(fail("expected MesosCommandCheck"))
        core.delay should be(CoreCheck.DefaultDelay)
        core.interval should be(CoreCheck.DefaultInterval)
        core.timeout should be(CoreCheck.DefaultTimeout)
      }
    }
  }

  "A MesosHttpCheck is converted correctly" when {
    "A MesosHttpCheck" should {
      val check = MesosHttpCheck()
      val raml = check.toRaml[AppCheck]

      behave like convertToProtobufThenToRAML(check, raml)

      "convert to a RAML AppCheck" in {
        raml.tcp should be(empty)
        raml.exec should be(empty)
        raml.http should be(defined)
        raml.intervalSeconds should be(check.interval.toSeconds)
        raml.http.get.port should be(check.port)
        raml.http.get.path should be(check.path)
        raml.timeoutSeconds should be(check.timeout.toSeconds)
        raml.delaySeconds should be(check.delay.toSeconds)
      }
    }
    "An AppCheck" should {
      "convert to a MesosHttpCheck" in {
        val check = AppCheck(http = Some(AppHttpCheck(port = Some(100))))
        val core = Some(check.fromRaml).collect {
          case c: MesosHttpCheck => c
        }.getOrElse(fail("expected MesosHttpCheck"))
        core.delay should be(CoreCheck.DefaultDelay)
        core.interval should be(CoreCheck.DefaultInterval)
        core.port should be(check.http.get.port)
        core.portIndex should be(check.http.get.portIndex)
        core.timeout should be(CoreCheck.DefaultTimeout)
      }
    }
  }

  "A MesosTcpCheck is converted correctly" when {
    "A MesosTcpCheck" should {
      val check = MesosTcpCheck()
      val raml = check.toRaml[AppCheck]

      behave like convertToProtobufThenToRAML(check, raml)

      "convert to a RAML AppCheck" in {
        raml.tcp should be(defined)
        raml.exec should be(empty)
        raml.http should be(empty)
        raml.intervalSeconds should be(check.interval.toSeconds)
        raml.tcp.get.port should be(check.port)
        raml.timeoutSeconds should be(check.timeout.toSeconds)
        raml.delaySeconds should be(check.delay.toSeconds)
      }
    }
    "An AppCheck" should {
      "convert to a MesosTcpCheck" in {
        val check = AppCheck(tcp = Some(AppTcpCheck(port = Some(100))))
        val core = Some(check.fromRaml).collect {
          case c: MesosTcpCheck => c
        }.getOrElse(fail("expected MesosTcpHealthCheck"))
        core.delay should be(CoreCheck.DefaultDelay)
        core.interval should be(CoreCheck.DefaultInterval)
        core.port should be(check.tcp.get.port)
        core.portIndex should be(check.tcp.get.portIndex)
        core.timeout should be(CoreCheck.DefaultTimeout)
      }
    }
  }
}
