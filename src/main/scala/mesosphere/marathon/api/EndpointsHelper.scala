package mesosphere.marathon
package api

import java.lang.{ StringBuilder => JavaStringBuilder }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.Container.PortMapping
import mesosphere.marathon.state.AppDefinition

object EndpointsHelper {
  /**
    * Traditionally, we only listed bridge or host networked apps in the text/plain tasks output; however, some
    * expressed a pressing need to see ip-per-container docker containers as well.
    *
    * We cannot lift this filter altogether as service ports are not assigned for Mesos containerizer with ip-per-tasks
    */
  private def shouldListApp(app: AppDefinition) =
    app.ipAddress.isEmpty || app.container.fold(false) { _.docker.nonEmpty }

  /**
    * Produces a script-friendly string representation of the supplied
    * apps' tasks.  The data columns in the result are separated by
    * the supplied delimiter string.
    */
  //scalastyle:off cyclomatic.complexity
  def appsToEndpointString(
    instancesMap: InstancesBySpec,
    apps: Seq[AppDefinition],
    delimiter: String): String = {
    val sb = new JavaStringBuilder
    for (app <- apps if shouldListApp(app)) {
      /* Note - this method is flawed and outputs the WRONG thing in the event of a portDefinition / portMapping
       * insertion or deletion.
       *
       * https://jira.mesosphere.com/browse/MARATHON-7407
       */
      val instances = instancesMap.specInstances(app.id)
      val cleanId = app.id.safePath

      val servicePorts = app.servicePorts

      if (servicePorts.isEmpty) {
        sb.append(cleanId).append(delimiter).append(' ').append(delimiter)
        for (instance <- instances if instance.isRunning) {
          sb.append(instance.agentInfo.host).append(' ')
        }
        sb.append('\n')
      } else {
        for ((port, i) <- servicePorts.zipWithIndex) {
          sb.append(cleanId).append(delimiter).append(port).append(delimiter)

          val ipPerTaskPortMapping = if (app.ipAddress.nonEmpty) containerPortMapping(app, i) else None
          val runningInstances = instances.withFilter(_.isRunning)
          ipPerTaskPortMapping match {
            case Some(portMapping) if portMapping.hostPort.isEmpty =>
              runningInstances.foreach { instance =>
                tryAppendContainerPort(sb, app, portMapping, instance, delimiter)
              }
            case Some(portMapping) if portMapping.hostPort.nonEmpty =>
              // the task hostPorts only contains an entry for each portMapping that has a hostPort defined
              // We need to compute and use the new index
              hostPortIndexOffset(app, i).foreach { computedHostPortIndex =>
                runningInstances.foreach { task =>
                  appendHostPortOrZero(sb, task, computedHostPortIndex, delimiter)
                }
              }
            case _ =>
              runningInstances.foreach { instance =>
                appendHostPortOrZero(sb, instance, i, delimiter)
              }
          }
          sb.append('\n')
        }
      }
    }
    sb.toString()
  }

  /**
    * Adjusts the index based on portMapping definitions. Expects that the specified index refers to a nonEmpty hostPort
    * portmapping record.
    */
  private def hostPortIndexOffset(app: AppDefinition, idx: Integer): Option[Integer] = {
    app.container.flatMap { container =>
      val pm = container.portMappings
      if (idx < 0 || idx >= pm.length) // index 2, length 2 invalid
        None // linter:ignore:DuplicateIfBranches
      else if (pm(idx).hostPort.isEmpty)
        None
      else
        // count each preceeding nonEmpty hostPort to get new index
        Some(pm.toIterator.take(idx).count(_.hostPort.nonEmpty))
    }
  }

  private def containerPortMapping(app: AppDefinition, portIdx: Integer): Option[PortMapping] =
    for {
      container <- app.container
      portMapping <- container.portMappings.lift(portIdx) // After MARATHON-7407 is addressed, this should probably throw.
    } yield portMapping

  /**
    * Append an entry to the provided string builder for the specified containerPort. If we cannot tell the
    * effectiveIpAddress, output nothing.
    */
  private def tryAppendContainerPort(
    sb: JavaStringBuilder, app: AppDefinition, portMapping: PortMapping, instance: Instance,
    delimiter: String): Unit = {
    for {
      task <- instance.tasksMap.values
      address <- task.status.networkInfo.effectiveIpAddress(app)
    } {
      sb.append(address).append(':').append(portMapping.containerPort).append(delimiter)
    }
  }
  /**
    * Append an entry to the provided string builder using the task's agent host IP and specified host port
    *
    * Note, at some-point, as a work-around to MARATHON-7407, it was decided that it would be a good idea to output port
    * 0 if no host port for the corresponding service port was found (this would happen in the event that you added a
    * new portMapping). This is rather nonsensical and should be removed when MARATHON-7407 is properly addressed.
    */
  private def appendHostPortOrZero(
    sb: JavaStringBuilder, instance: Instance, portIdx: Integer, delimiter: String): Unit = {
    instance.tasksMap.values.withFilter(_.status.condition.isActive).foreach { task =>
      val taskPort = task.status.networkInfo.hostPorts.lift(portIdx).getOrElse(0)
      sb.append(instance.agentInfo.host).append(':').append(taskPort).append(delimiter)
    }
  }
}
