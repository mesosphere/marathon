package mesosphere.marathon.plugin.event

import mesosphere.marathon.plugin.Plugin

trait EventListener extends Plugin {

  def handleEvent(o: Object): Unit

}
