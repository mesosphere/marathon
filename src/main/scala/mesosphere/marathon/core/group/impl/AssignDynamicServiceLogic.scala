package mesosphere.marathon
package core.group.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.state._

import scala.collection.immutable.Seq

object AssignDynamicServiceLogic extends StrictLogging {

  /**
    * Checks whether newApp is new or changed.
    * @param original The original root group from before the update.
    * @param newApp The new app that is tested.
    * @return true if app is new or an updated, false otherwise.
    */
  private def changedOrNew(original: RootGroup, newApp: AppDefinition): Boolean = {
    original.app(newApp.id).forall { _.isUpgrade(newApp) }
  }

  private def mergeServicePortsAndPortDefinitions(
    portDefinitions: Seq[PortDefinition],
    servicePorts: Seq[Int]): Seq[PortDefinition] =
    if (portDefinitions.nonEmpty)
      portDefinitions.zipAll(servicePorts, AppDefinition.RandomPortDefinition, AppDefinition.RandomPortValue).map {
        case (portDefinition, servicePort) => portDefinition.copy(port = servicePort)
      }
    else Seq.empty

  /**
    * CONTAINS SIDE EFFECTS!!!
    *
    * Given some app, replace all service port 0 specifications with an assigned service port.
    *
    * Assignment logic is:
    * 1) First, scan for service ports used in old app, but are not specified in the new app. Use these first.
    * 2) Second, consume from provided unassignedPortsIterator
    *
    * If no ports are assigned, there are no side effects.
    *
    * @return The updated app definition with assigned service ports
    */
  private def assignPorts(newApp: AppDefinition, oldApp: Option[AppDefinition], portRange: Range,
    unassignedPortsIterator: Iterator[Int]): AppDefinition = {
    /* All ports that are already assigned in old app definition, but not used in the new definition
     * if the app uses dynamic ports (0), it will get always the same ports assigned */
    val assignedAndAvailable: Seq[Int] =
      oldApp match {
        case Some(oldApp) =>
          oldApp.servicePorts.filter { p: Int => portRange.contains(p) && !newApp.servicePorts.contains(p) }
        case None => Nil
      }

    val freePortsIterator = assignedAndAvailable.iterator ++ unassignedPortsIterator

    val servicePorts: Seq[Int] = newApp.servicePorts.map {
      case 0 =>
        if (freePortsIterator.hasNext)
          freePortsIterator.next()
        else
          throw new PortRangeExhaustedException(portRange.min, portRange.max)
      case port => port
    }

    val updatedContainer = newApp.container.find(_.portMappings.nonEmpty).map { container =>
      val newMappings = container.portMappings.zip(servicePorts).map {
        case (portMapping, servicePort) => portMapping.copy(servicePort = servicePort)
      }
      container.copyWith(portMappings = newMappings)
    }

    newApp.copy(
      portDefinitions = mergeServicePortsAndPortDefinitions(newApp.portDefinitions, servicePorts),
      container = updatedContainer.orElse(newApp.container))
  }

  def assignDynamicServicePorts(portRange: Range, from: RootGroup, to: RootGroup): RootGroup = {
    /* Note: We consider the "from" rootGroup servicePorts as well, since these are reserved for re-use for a specific
     * app in the case that servicePorts are re-posted all as 0's.
     */
    val usedServicePorts: Set[Int] =
      (from.transitiveApps ++ to.transitiveApps).flatMap(_.servicePorts).toSet
    val unassignedPortsIterator = portRange.iterator
      .filter { p => !usedServicePorts.contains(p) }
      .map { port =>
        logger.debug(s"Take next configured free port: $port")
        port
      }
    val dynamicApps: Iterable[AppDefinition] =
      to.transitiveApps
        .filter { newApp => changedOrNew(from, newApp) }
        .map {
          // assign values for service ports that the user has left "blank" (set to zero)
          case app: AppDefinition if app.hasDynamicServicePorts =>
            assignPorts(app, from.app(app.id), portRange, unassignedPortsIterator)
          case app: AppDefinition =>
            // Always set the ports to service ports, even if we do not have dynamic ports in our port mappings
            app.copy(
              portDefinitions = mergeServicePortsAndPortDefinitions(app.portDefinitions, app.servicePorts)
            )
        }

    dynamicApps.foldLeft(to) { (rootGroup, app) =>
      rootGroup.updateApp(app.id, _ => app, app.version)
    }
  }
}
