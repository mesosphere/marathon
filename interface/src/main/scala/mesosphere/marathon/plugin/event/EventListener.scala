package mesosphere.marathon.plugin.event

import mesosphere.marathon.plugin.Plugin

/**
  * Base trait for all events.
  * TODO: add a more specific event hierarchy.
  */
trait Event {
  /**
    * The type of event
    */
  val eventType: String

  /**
    * The timestamp of the event
    */
  val timestamp: String
}

/**
  * An event listener plugin
  */
trait EventListener extends Plugin {

  def handleEvent(event: Event): Unit

}
