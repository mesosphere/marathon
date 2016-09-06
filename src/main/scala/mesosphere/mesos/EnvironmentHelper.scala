package mesosphere.mesos

import mesosphere.marathon.state.AppDefinition

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.util.Random

object EnvironmentHelper {
  val maxEnvironmentVarLength = 512
  val labelEnvironmentKeyPrefix = "MARATHON_APP_LABEL_"
  val maxVariableLength = maxEnvironmentVarLength - labelEnvironmentKeyPrefix.length

  // portsEnv generates $PORT{x} and $PORT_{y} environment variables, wherein `x` is an index into
  // the portDefinitions or portMappings array and `y` is a non-zero port specifically requested by
  // the application specification.
  //
  // @param requestedPorts are either declared container ports (if port mappings are specified) or host ports;
  // may be 0's
  // @param effectivePorts resolved non-dynamic host ports allocated from Mesos resource offers
  // @return a dictionary of variables that should be added to a tasks environment
  //scalastyle:off cyclomatic.complexity method.length
  def portsEnv(
    requestedPorts: Seq[Int],
    effectivePorts: Seq[Option[Int]],
    portNames: Seq[Option[String]]): Map[String, String] = {
    if (effectivePorts.isEmpty) {
      Map.empty
    } else {
      val env = Map.newBuilder[String, String]
      val generatedPortsBuilder = Map.newBuilder[Int, Int] // index -> container port

      object ContainerPortGenerator {
        // track which port numbers are already referenced by PORT_xxx envvars
        lazy val consumedPorts = mutable.Set(requestedPorts: _*) ++= effectivePorts.flatten
        val maxPort: Int = 65535 - 1024

        // carefully pick a container port that doesn't overlap with other ports used by this
        // container. and avoid ports in the range (0 - 1024)
        def next: Int = {
          val p = Random.nextInt(maxPort) + 1025
          if (!consumedPorts.contains(p)) {
            consumedPorts += p
            p
          } else next // TODO(jdef) **highly** unlikely, but still possible that the port range could be exhausted
        }
      }

      effectivePorts.zipWithIndex.foreach {
        // matches fixed or dynamic host port assignments
        case (Some(effectivePort), portIndex) =>
          env += (s"PORT$portIndex" -> effectivePort.toString)

        // matches container-port-only mappings; no host port was defined for this mapping
        case (None, portIndex) =>
          requestedPorts.lift(portIndex) match {
            case Some(containerPort) if containerPort == AppDefinition.RandomPortValue =>
              val randomPort = ContainerPortGenerator.next
              generatedPortsBuilder += portIndex -> randomPort
              env += (s"PORT$portIndex" -> randomPort.toString)
            case Some(containerPort) if containerPort != AppDefinition.RandomPortValue =>
              env += (s"PORT$portIndex" -> containerPort.toString)
          }
      }

      val generatedPorts = generatedPortsBuilder.result
      requestedPorts.zip(effectivePorts).zipWithIndex.foreach {
        case ((requestedPort, Some(effectivePort)), _) if (requestedPort != AppDefinition.RandomPortValue) =>
          env += (s"PORT_$requestedPort" -> effectivePort.toString)
        case ((requestedPort, Some(effectivePort)), _) if (requestedPort == AppDefinition.RandomPortValue) =>
          env += (s"PORT_$effectivePort" -> effectivePort.toString)
        case ((requestedPort, None), _) if (requestedPort != AppDefinition.RandomPortValue) =>
          env += (s"PORT_$requestedPort" -> requestedPort.toString)
        case ((requestedPort, None), portIndex) if (requestedPort == AppDefinition.RandomPortValue) =>
          val generatedPort = generatedPorts(portIndex)
          env += (s"PORT_$generatedPort" -> generatedPort.toString)
      }

      portNames.zip(effectivePorts).foreach {
        case (Some(portName), Some(effectivePort)) =>
          env += (s"PORT_${portName.toUpperCase}" -> effectivePort.toString)
        // TODO(jdef) port name envvars for generated container ports
        case _ =>
      }

      val allAssigned = effectivePorts.flatten ++ generatedPorts.values
      allAssigned.headOption.foreach { port => env += ("PORT" -> port.toString) }
      env += ("PORTS" -> allAssigned.mkString(","))
      env.result()
    }
  }

  def labelsToEnvVars(labels: Map[String, String]): Map[String, String] = {

    def escape(name: String): String = name.replaceAll("[^a-zA-Z0-9_]+", "_").toUpperCase

    val validLabels = labels.collect {
      case (key, value) if key.length < maxVariableLength
        && value.length < maxEnvironmentVarLength => escape(key) -> value
    }

    val names = Map("MARATHON_APP_LABELS" -> validLabels.keys.mkString(" "))
    val values = validLabels.map { case (key, value) => s"$labelEnvironmentKeyPrefix$key" -> value }
    names ++ values
  }
}
