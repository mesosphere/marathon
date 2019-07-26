package mesosphere.marathon
package raml

import mesosphere.marathon.state.AbsolutePathId

/**
  * The interface to a group visitor pattern.
  */
trait GroupUpdateVisitor[A, G] {
  /**
    * Visit the current group. It should not change `group.apps` or `group.groups`.
    *
    * @param thisGroup The current group to visit.
    * @return The visit result.
    */
  def visit(thisGroup: raml.GroupUpdate): G

  /**
    * Factory method for a visitor of the direct children of this group.
    *
    * Eg. if this group is `/prod` its children `/prod/db` and `/prod/api` will be visited.
    *
    * @return The [[GroupUpdateVisitor]] for the children. See [[mesosphere.marathon.api.v2.RootGroupVisitor.childGroupVisitor()]]
    *         for an example.
    */
  def childGroupVisitor(): GroupUpdateVisitor[A, G]

  /**
    * factory method for a visitor for all direct apps in `group.apps`. The visitor will not visit
    * apps in child groups.
    *
    * @return The new visitor, eg [[mesosphere.marathon.api.v2.AppNormalizeVisitor]].
    */
  def appVisitor(): AppVisitor[A]
}

/**
  * The interface to an app visitor pattern.
  */
trait AppVisitor[R] {
  /**
    * Visit an app.
    *
    * @param app The app that is visited.
    * @param groupId The absolute path of the group that holds the app. Eg the group `/prod/db` might
    *                hold the app `/prod/db/postgresql`.
    * @return The result of the visit.
    */
  def visit(app: raml.App, groupId: AbsolutePathId): R
}

