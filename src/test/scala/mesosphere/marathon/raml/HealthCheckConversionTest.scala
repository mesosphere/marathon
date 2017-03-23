package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.core.health.{ HealthCheck => CoreHealthCheck, _ }
import scala.concurrent.duration._

class HealthCheckConversionTest extends UnitTest {

  def convertToProtobufThenToRAML(check: => CoreHealthCheck, raml: => AppHealthCheck): Unit = {
    "convert to protobuf, then to RAML" in {
      val proto2raml = check.toProto.toRaml
      proto2raml should be(raml)
    }
  }

  "A MarathonHttpHealthCheck is converted correctly" when {
    "A core MarathonHttpHealthCheck" should {

      val check = MarathonHttpHealthCheck()
      val raml = check.toRaml[AppHealthCheck]

      behave like convertToProtobufThenToRAML(check, raml)

      "convert to a RAML AppHealthCheck" in {
        raml.protocol should be(AppHealthCheckProtocol.Http)
        raml.command should be(empty)
        raml.gracePeriodSeconds should be(check.gracePeriod.toSeconds)
        raml.ignoreHttp1xx should be(Some(check.ignoreHttp1xx))
        raml.intervalSeconds should be(check.interval.toSeconds)
        raml.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
        raml.path should be(check.path)
        raml.port should be(check.port)
        raml.timeoutSeconds should be(check.timeout.toSeconds)
      }
    }
    "An AppHealthCheck" should {
      "convert to a core MarathonHttpHealthCheck" in {
        val check = AppHealthCheck(protocol = AppHealthCheckProtocol.Http)
        val core = Some(check.fromRaml).collect {
          case c: MarathonHttpHealthCheck => c
        }.getOrElse(fail("expected MarathonHttpHealthCheck"))
        core.protocol should be(Protos.HealthCheckDefinition.Protocol.HTTP)
        core.gracePeriod should be(check.gracePeriodSeconds.seconds)
        core.ignoreHttp1xx should be(false)
        core.interval should be(check.intervalSeconds.seconds)
        core.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
        core.path should be(check.path)
        core.port should be(check.port)
        core.portIndex should be (check.portIndex)
        core.timeout should be(check.timeoutSeconds.seconds)
      }
    }
  }

  "A MarathonTcpHealthCheck is converted correctly" when {
    "A core MarathonTcpHealthCheck" should {
      val check = MarathonTcpHealthCheck()
      val raml = check.toRaml[AppHealthCheck]

      behave like convertToProtobufThenToRAML(check, raml)

      "convert to a RAML AppHealthCheck" in {
        raml.protocol should be(AppHealthCheckProtocol.Tcp)
        raml.command should be(empty)
        raml.gracePeriodSeconds should be(check.gracePeriod.toSeconds)
        raml.ignoreHttp1xx should be(None)
        raml.intervalSeconds should be(check.interval.toSeconds)
        raml.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
        raml.path should be(empty)
        raml.port should be(check.port)
        raml.timeoutSeconds should be(check.timeout.toSeconds)
      }
    }
    "An AppHealthCheck" should {
      "convert to a MarathonTcpHealthCheck" in {
        val check = AppHealthCheck(protocol = AppHealthCheckProtocol.Tcp)
        val core = Some(check.fromRaml).collect {
          case c: MarathonTcpHealthCheck => c
        }.getOrElse(fail("expected MarathonTcpHealthCheck"))
        core.gracePeriod should be(check.gracePeriodSeconds.seconds)
        core.interval should be(check.intervalSeconds.seconds)
        core.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
        core.port should be(check.port)
        core.portIndex should be (check.portIndex)
        core.timeout should be(check.timeoutSeconds.seconds)
      }
    }
  }

  "A MesosCommandHealthCheck is converted correctly" when {
    "A core MesosCommandHealthCheck" should {
      val check = MesosCommandHealthCheck(command = state.Command("test"))
      val raml = check.toRaml[AppHealthCheck]

      behave like convertToProtobufThenToRAML(check, raml)

      "convert to a RAML AppHealthCheck" in {
        raml.protocol should be(AppHealthCheckProtocol.Command)
        raml.command should be(Some(CommandCheck("test")))
        raml.gracePeriodSeconds should be(check.gracePeriod.toSeconds)
        raml.ignoreHttp1xx should be(None)
        raml.intervalSeconds should be(check.interval.toSeconds)
        raml.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
        raml.path should be(empty)
        raml.port should be(empty)
        raml.timeoutSeconds should be(check.timeout.toSeconds)
        raml.delaySeconds should be(check.delay.toSeconds)
      }
    }
    "An AppHealthCheck" should {
      "convert to a MesosCommandHealthCheck" in {
        val check = AppHealthCheck(protocol = AppHealthCheckProtocol.Command, command = Some(CommandCheck("foo")))
        val core = Some(check.fromRaml).collect {
          case c: MesosCommandHealthCheck => c
        }.getOrElse(fail("expected MesosCommandHealthCheck"))
        core.command should be(state.Command("foo"))
        core.gracePeriod should be(check.gracePeriodSeconds.seconds)
        core.interval should be(check.intervalSeconds.seconds)
        core.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
        core.timeout should be(check.timeoutSeconds.seconds)
        core.delay should be(CoreHealthCheck.DefaultDelay)
      }
    }
  }

  "A MesosHttpHealthCheck is converted correctly" when {
    "A core MesosHttpHealthCheck" should {
      val check = MesosHttpHealthCheck()
      val raml = check.toRaml[AppHealthCheck]

      behave like convertToProtobufThenToRAML(check, raml)

      "convert to a RAML AppHealthCheck" in {
        raml.protocol should be(AppHealthCheckProtocol.MesosHttp)
        raml.command should be(empty)
        raml.gracePeriodSeconds should be(check.gracePeriod.toSeconds)
        raml.ignoreHttp1xx should be(None)
        raml.intervalSeconds should be(check.interval.toSeconds)
        raml.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
        raml.path should be(check.path)
        raml.port should be(check.port)
        raml.timeoutSeconds should be(check.timeout.toSeconds)
        raml.delaySeconds should be(check.delay.toSeconds)
      }
    }
    "An AppHealthCheck" should {
      "convert to a MesosHttpHealthCheck" in {
        val check = AppHealthCheck(protocol = AppHealthCheckProtocol.MesosHttp)
        val core = Some(check.fromRaml).collect {
          case c: MesosHttpHealthCheck => c
        }.getOrElse(fail("expected MesosHttpHealthCheck"))
        core.protocol should be(Protos.HealthCheckDefinition.Protocol.MESOS_HTTP)
        core.gracePeriod should be(check.gracePeriodSeconds.seconds)
        core.delay should be(CoreHealthCheck.DefaultDelay)
        core.interval should be(check.intervalSeconds.seconds)
        core.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
        core.path should be(check.path)
        core.port should be(check.port)
        core.portIndex should be (check.portIndex)
        core.timeout should be(check.timeoutSeconds.seconds)
      }
    }
  }

  "A MesosTcpHealthCheck is converted correctly" when {
    "A MesosTcpHealthCheck" should {
      val check = MesosTcpHealthCheck()
      val raml = check.toRaml[AppHealthCheck]

      behave like convertToProtobufThenToRAML(check, raml)

      "convert to a RAML AppHealthCheck" in {
        raml.protocol should be(AppHealthCheckProtocol.MesosTcp)
        raml.command should be(empty)
        raml.gracePeriodSeconds should be(check.gracePeriod.toSeconds)
        raml.ignoreHttp1xx should be(None)
        raml.intervalSeconds should be(check.interval.toSeconds)
        raml.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
        raml.path should be(empty)
        raml.port should be(check.port)
        raml.timeoutSeconds should be(check.timeout.toSeconds)
        raml.delaySeconds should be(check.delay.toSeconds)
      }
    }
    "An AppHealthCheck" should {
      "convert to a MesosTcpHealthCheck" in {
        val check = AppHealthCheck(protocol = AppHealthCheckProtocol.MesosTcp)
        val core = Some(check.fromRaml).collect {
          case c: MesosTcpHealthCheck => c
        }.getOrElse(fail("expected MesosTcpHealthCheck"))
        core.gracePeriod should be(check.gracePeriodSeconds.seconds)
        core.delay should be(CoreHealthCheck.DefaultDelay)
        core.interval should be(check.intervalSeconds.seconds)
        core.maxConsecutiveFailures should be(check.maxConsecutiveFailures)
        core.port should be(check.port)
        core.portIndex should be(check.portIndex)
        core.timeout should be(check.timeoutSeconds.seconds)
      }
    }
  }
}
