package mesosphere.marathon.core.appinfo

import mesosphere.marathon.state.AppDefinition

trait AppSelector {
  def matches(app: AppDefinition): Boolean
}

object AppSelector {
  def apply(matchesFunc: AppDefinition => Boolean): AppSelector = new AppSelector {
    override def matches(app: AppDefinition): Boolean = matchesFunc(app)
  }

  def all: AppSelector = AppSelector(_ => true)

  def forall(selectors: Iterable[AppSelector]): AppSelector = new AllAppSelectorsMustMatch(selectors)

  private[appinfo] class AllAppSelectorsMustMatch(selectors: Iterable[AppSelector]) extends AppSelector {
    override def matches(app: AppDefinition): Boolean = selectors.forall(_.matches(app))
  }
}
