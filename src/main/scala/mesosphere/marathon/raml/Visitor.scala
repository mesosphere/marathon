package mesosphere.marathon
package raml

import mesosphere.marathon.state.{AbsolutePathId, PathId}

/**
  * The interface to a group visitor pattern.
  *
  * @tparam A The return type of the [[AppVisitor.visit()]] call.
  * @tparam G The return type of [[GroupUpdateVisitor.visit()]].
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
    * Factory method for a visitor for all direct apps in `group.apps`. The visitor will not visit
    * apps in child groups.
    *
    * @return The new visitor, eg [[mesosphere.marathon.api.v2.AppNormalizeVisitor]].
    */
  def appVisitor(): AppVisitor[A]

  /**
    * Accumulate results from [[visit()]], [[childGroupVisitor()]]'s visits to all direct child groups
    * and [[appVisitor()]]'s visits to all apps.
    *
    * @param base
    * @param thisGroup The result from the visit to the current group.
    * @param children The result from all visits to direct child groups.
    * @param apps The result from all visits to apps in this group.
    * @return The final accumulated result.
    */
  def done(base: AbsolutePathId, thisGroup: G, children: Option[Iterator[G]], apps: Option[Iterator[A]]): G
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

object GroupUpdateVisitor {

  /**
    * Dispatch the visitor on the group update and its children.
    *
    * @param groupUpdate The group update that will be visited.
    * @param base The absolute path of group being updated.
    * @param visitor
    * @return The group update returned by the visitor.
    */
  def dispatch[A, R](groupUpdate: raml.GroupUpdate, base: AbsolutePathId, visitor: GroupUpdateVisitor[A, R]): R = {
    val visitedGroup: R = visitor.visit(groupUpdate)

    // Visit each child group.
    val childGroupVisitor = visitor.childGroupVisitor()
    val visitedChildren: Option[Iterator[R]] = groupUpdate.groups.map(_.toIterator.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(base)
      dispatch(childGroup, absoluteChildGroupPath, childGroupVisitor)
    })

    // Visit each app.
    val appVisitor = visitor.appVisitor()
    val visitedApps: Option[Iterator[A]] = groupUpdate.apps.map(_.toIterator.map { app =>
      appVisitor.visit(app, base)
    })

    visitor.done(base, visitedGroup, visitedChildren, visitedApps)
  }
}

