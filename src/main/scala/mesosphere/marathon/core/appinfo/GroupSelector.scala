package mesosphere.marathon.core.appinfo

import mesosphere.marathon.core.appinfo.AppSelector.AllAppSelectorsMustMatch
import mesosphere.marathon.state.{ AppDefinition, Group }
import scala.collection.immutable.Seq

/**
  * Select matching groups and apps.
  */
trait GroupSelector extends AppSelector {

  /**
    * True if given group matches the criteria, otherwise false.
    */
  def matches(group: Group): Boolean
}

object GroupSelector {
  def apply(matchesApp: AppDefinition => Boolean, matchesGroup: Group => Boolean): GroupSelector = new GroupSelector {
    override def matches(app: AppDefinition): Boolean = matchesApp(app)
    override def matches(group: Group): Boolean = matchesGroup(group)
  }
  def forall(selectors: Seq[GroupSelector]): GroupSelector = new AllGroupSelectorsMustMatch(selectors)

  def all: GroupSelector = GroupSelector(_ => true, _ => true)

  private[appinfo] class AllGroupSelectorsMustMatch(selectors: Seq[GroupSelector])
      extends AllAppSelectorsMustMatch(selectors) with GroupSelector {
    override def matches(group: Group): Boolean = selectors.forall(_.matches(group))
  }
}
