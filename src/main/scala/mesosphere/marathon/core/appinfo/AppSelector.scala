package mesosphere.marathon.core.appinfo

import mesosphere.marathon.state.AppDefinition
import scala.collection.immutable.Seq

trait AppSelector {
  def matches(app: AppDefinition): Boolean
}

object AppSelector {
  def apply(matchesFunc: AppDefinition => Boolean): AppSelector = new AppSelector {
    override def matches(app: AppDefinition): Boolean = matchesFunc(app)
  }

  def all: AppSelector = AppSelector(_ => true)

  def forall(selectors: Seq[AppSelector]): AppSelector = new AllAppSelectorsMustMatch(selectors)

  private[appinfo] class AllAppSelectorsMustMatch(selectors: Seq[AppSelector]) extends AppSelector {
    override def matches(app: AppDefinition): Boolean = selectors.forall(_.matches(app))
  }
}
