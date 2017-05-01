package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.Implicits._
import org.apache.mesos.{ Protos => Mesos }

class EnvVarConversionTest extends UnitTest {

  import Environment.Implicits._

  "EnvVarConversion" when {
    "converting from proto to RAML" should {
      def convertEnv(subtitle: String, sdf: Fixture => Protos.ServiceDefinition, expected: Map[String, EnvVarValueOrSecret]): Unit =
        s"succeed when $subtitle" in new Fixture {
          val sd: Protos.ServiceDefinition = sdf(this)
          val converted = sd.whenOrElse(
            _.hasCmd,
            s => (s.getCmd.getEnvironment.getVariablesList.to[Seq], s.getEnvVarReferencesList.to[Seq]).toRaml,
            App.DefaultEnv)
          converted should be(expected)
        }

      behave like convertEnv("no env present", _.emptyService, Map.empty)

      val simpleVars = Map("a" -> "b", "q" -> "z")
      behave like convertEnv("simple vars present", _.withVars(vars = simpleVars), Environment(simpleVars))

      val secretVars = Map("var1" -> "secret1", "var2" -> "secret2")
      behave like convertEnv("only secrets present", _.withSecrets(secretRefs = secretVars), Environment().withSecrets(secretVars))

      behave like convertEnv(
        "simple and secret refs present",
        f => f.withVars(f.withSecrets(secretRefs = secretVars), vars = simpleVars),
        Environment(simpleVars).withSecrets(secretVars)
      )
    }
  }

  class Fixture {
    val emptyService: Protos.ServiceDefinition = Protos.ServiceDefinition.newBuilder()
      .setCmd(Mesos.CommandInfo.newBuilder())
      .setExecutor("//cmd")
      .setId("/foo")
      .setInstances(1)
      .build

    def withVars(sd: Protos.ServiceDefinition = emptyService, vars: Map[String, String]): Protos.ServiceDefinition = {
      val env = if (sd.getCmd.hasEnvironment) sd.getCmd.getEnvironment.toBuilder else Mesos.Environment.newBuilder()
      vars.foreach {
        case (k, v) =>
          env.addVariables(Mesos.Environment.Variable.newBuilder().setName(k).setValue(v))
      }
      val cmd = sd.getCmd.toBuilder
      cmd.setEnvironment(env)
      sd.toBuilder.setCmd(cmd).build()
    }

    def withSecrets(sd: Protos.ServiceDefinition = emptyService, secretRefs: Map[String, String]): Protos.ServiceDefinition = {
      val builder = sd.toBuilder
      secretRefs.foreach {
        case (envVarName, secretName) =>
          builder.addEnvVarReferencesBuilder()
            .setType(Protos.EnvVarReference.Type.SECRET)
            .setName(envVarName)
            .setSecretRef(Protos.EnvVarSecretRef.newBuilder().setSecretId(secretName))
      }
      builder.build
    }
  }

}
