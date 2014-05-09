package mesosphere.marathon.upgrade

import mesosphere.marathon.state.{Timestamp, PersistenceStore}
import mesosphere.marathon.api.v2.Group

/**
 * Date: 08.05.14
 * Time: 17:35
 */
class GroupRepository(store: PersistenceStore[Group]) {

  def group(id: String, version: Timestamp) : Group = ???


}
