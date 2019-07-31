package mesosphere.marathon
package raml

import mesosphere.marathon.state.{AbsolutePathId, PathId}

/**
  * The interface to a group visitor pattern.
  *
  * @tparam I The type of the update
  * @tparam A The return type of the [[AppVisitor.visit()]] call.
  * @tparam G The return type of [[GroupUpdateVisitor.visit()]].
  */
trait GroupUpdateVisitor[I, AI, AR, G] {

  /**
    * Visit the current group. It should not change `group.apps` or `group.groups`.
    *
    * @param thisGroup The current group to visit.
    * @return The visit result.
    */
  def visit(thisGroup: I): G

  /**
    * Factory method for a visitor of the direct children of this group.
    *
    * Eg. if this group is `/prod` its children `/prod/db` and `/prod/api` will be visited.
    *
    * @return The [[GroupUpdateVisitor]] for the children. See [[mesosphere.marathon.api.v2.RootGroupVisitor.childGroupVisitor()]]
    *         for an example.
    */
  def childGroupVisitor(): GroupUpdateVisitor[I, AI, AR, G]

  /**
    * Factory method for a visitor for all direct apps in `group.apps`. The visitor will not visit
    * apps in child groups.
    *
    * @return The new visitor, eg [[mesosphere.marathon.api.v2.AppNormalizeVisitor]].
    */
  def appVisitor(): AppVisitor[AI, AR]

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
  def done(base: AbsolutePathId, thisGroup: G, children: Option[Vector[G]], apps: Option[Vector[AR]]): G

  def andThen[A2R, G2](other: GroupUpdateVisitor[G, AR, A2R, G2]): GroupUpdateVisitor[I, AI, A2R, G2] = GroupUpdateVisitorCompose(this, other)
}

case class GroupUpdateVisitorCompose[I, A1I, A1R, A2R, G1, G2](first: GroupUpdateVisitor[I, A1I, A1R, G1], other: GroupUpdateVisitor[G1, A1R, A2R, G2]) extends GroupUpdateVisitor[I, A1I, A2R, G2] {
  override def visit(thisGroup: I): G2 = other.visit(first.visit(thisGroup))

  override def childGroupVisitor(): GroupUpdateVisitor[I, A1I, A2R, G2] = GroupUpdateVisitorCompose(first.childGroupVisitor(), other.childGroupVisitor())

  override def appVisitor(): AppVisitor[A1I, A2R] = AppVisitorCompose(first.appVisitor(), other.appVisitor())

  override def done(base: AbsolutePathId, thisGroup: G2, children: Option[Vector[G2]], apps: Option[Vector[A2R]]): G2 = other.done(base, thisGroup, children, apps)
}

/**
  * The interface to an app visitor pattern.
  */
trait AppVisitor[I, R] {
  /**
    * Visit an app.
    *
    * @param app The app that is visited.
    * @param groupId The absolute path of the group that holds the app. Eg the group `/prod/db` might
    *                hold the app `/prod/db/postgresql`.
    * @return The result of the visit.
    */
  def visit(app: I, groupId: AbsolutePathId): R
}

case class AppVisitorCompose[I, R1, R2](first: AppVisitor[I, R1], second: AppVisitor[R1, R2]) extends AppVisitor[I, R2] {

  override def visit(app: I, groupId: AbsolutePathId): R2 = second.visit(first.visit(app, groupId), groupId)
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
  def dispatch[A, R](groupUpdate: raml.GroupUpdate, base: AbsolutePathId, visitor: GroupUpdateVisitor[raml.GroupUpdate, raml.App, A, R]): R = {
    val visitedGroup: R = visitor.visit(groupUpdate)

    // Visit each child group.
    val childGroupVisitor = visitor.childGroupVisitor()
    val visitedChildren: Option[Vector[R]] = groupUpdate.groups.map(_.toVector.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(base)
      dispatch(childGroup, absoluteChildGroupPath, childGroupVisitor)
    })

    // Visit each app.
    val appVisitor = visitor.appVisitor()
    val visitedApps: Option[Vector[A]] = groupUpdate.apps.map(_.toVector.map { app =>
      appVisitor.visit(app, base)
    })

    visitor.done(base, visitedGroup, visitedChildren, visitedApps)
  }
}

