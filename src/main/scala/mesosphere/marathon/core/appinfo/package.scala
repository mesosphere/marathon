package mesosphere.marathon
package core

import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.{ AppDefinition, Group }

package object appinfo {

  type AppSelector = Selector[AppDefinition]
  type GroupSelector = Selector[Group]
  type PodSelector = Selector[PodDefinition]
}
