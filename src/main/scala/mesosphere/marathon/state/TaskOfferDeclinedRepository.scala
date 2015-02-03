package mesosphere.marathon.state

import mesosphere.marathon.event.TaskOfferDeclinedEvent

import scala.collection.mutable

class TaskOffersDeclinedRepository() {

  protected[this] val tasksDeclined = mutable.Map[PathId, TaskOfferDeclinedEvent]()

  def store(id: PathId, value: TaskOfferDeclinedEvent): Unit =
    synchronized { tasksDeclined(id) = value }

  def expunge(id: PathId): Unit =
    synchronized { tasksDeclined -= id }

  def current(id: PathId): Option[TaskOfferDeclinedEvent] = tasksDeclined.get(id)

}
