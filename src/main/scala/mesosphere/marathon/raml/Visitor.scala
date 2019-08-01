package mesosphere.marathon
package raml

import com.wix.accord.Failure
import mesosphere.marathon.state.{AbsolutePathId, PathId}

/**
  * The interface to a group visitor pattern.
  *
  * @tparam GroupIn The type of the update
  * @tparam AppIn The input type to [[AppVisitor.visit()]].
  * @tparam AppOut The return type of the [[AppVisitor.visit()]] call.
  * @tparam GroupOut The return type of [[GroupUpdateVisitor.visit()]].
  */
trait GroupUpdateVisitor[GroupIn, AppIn, AppOut, GroupOut] {

  /**
    * Visit the current group. It should not change `group.apps` or `group.groups`.
    *
    * @param thisGroup The current group to visit.
    * @return The visit result.
    */
  def visit(thisGroup: GroupIn): GroupOut

  /**
    * Factory method for a visitor of the direct children of this group.
    *
    * Eg. if this group is `/prod` its children `/prod/db` and `/prod/api` will be visited.
    *
    * @return The [[GroupUpdateVisitor]] for the children. See [[mesosphere.marathon.api.v2.RootGroupVisitor.childGroupVisitor()]]
    *         for an example.
    */
  def childGroupVisitor(): GroupUpdateVisitor[GroupIn, AppIn, AppOut, GroupOut]

  /**
    * Factory method for a visitor for all direct apps in `group.apps`. The visitor will not visit
    * apps in child groups.
    *
    * @return The new visitor, eg [[mesosphere.marathon.api.v2.AppNormalizeVisitor]].
    */
  def appVisitor(): AppVisitor[AppIn, AppOut]

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
  def done(base: AbsolutePathId, thisGroup: GroupOut, children: Option[Vector[GroupOut]], apps: Option[Vector[AppOut]]): GroupOut

  /**
    * Composes this visitor with and outer one.
    *
    * @param outer The outer visitor which visits the results of this visitor.
    * @tparam OuterAppResult The return type of the outer app visits.
    * @tparam OuterResult The return type of the outer group visits.
    * @return A composed visitor.
    */
  def andThen[OuterAppResult, OuterResult](outer: GroupUpdateVisitor[GroupOut, AppOut, OuterAppResult, OuterResult]): GroupUpdateVisitor[GroupIn, AppIn, OuterAppResult, OuterResult] = GroupUpdateVisitorCompose(this, outer)
}

/**
  * Represents the composition of two group update visitor.
  *
  * @param inner The visitor that is first applied.
  * @param outer The visitor that is applied to the result of the inner visitor.
  * @tparam InnerGroupIn The input type for the inner group visitor.
  * @tparam InnerAppIn The app input type for the inner group visitor.
  * @tparam InnerAppOut The output type of the inner visitor. It is the input type for the outer.
  * @tparam OuterAppOut The app output type of the outer visitor.
  * @tparam InnerGroupOut The group output type of the inner group visitor. It is the input type of
  *                       the outer visitor.
  * @tparam OuterGroupOut The group output type of the outer group visitor.
  */
case class GroupUpdateVisitorCompose[InnerGroupIn, InnerAppIn, InnerAppOut, OuterAppOut, InnerGroupOut, OuterGroupOut](
    inner: GroupUpdateVisitor[InnerGroupIn, InnerAppIn, InnerAppOut, InnerGroupOut],
    outer: GroupUpdateVisitor[InnerGroupOut, InnerAppOut, OuterAppOut, OuterGroupOut]) extends GroupUpdateVisitor[InnerGroupIn, InnerAppIn, OuterAppOut, OuterGroupOut] {

  override def visit(thisGroup: InnerGroupIn): OuterGroupOut = outer.visit(inner.visit(thisGroup))

  override def childGroupVisitor(): GroupUpdateVisitor[InnerGroupIn, InnerAppIn, OuterAppOut, OuterGroupOut] = GroupUpdateVisitorCompose(inner.childGroupVisitor(), outer.childGroupVisitor())

  override def appVisitor(): AppVisitor[InnerAppIn, OuterAppOut] = AppVisitorCompose(inner.appVisitor(), outer.appVisitor())

  override def done(base: AbsolutePathId, thisGroup: OuterGroupOut, children: Option[Vector[OuterGroupOut]], apps: Option[Vector[OuterAppOut]]): OuterGroupOut = outer.done(base, thisGroup, children, apps)
}

/**
  * The interface to an app visitor pattern.
  *
  * @tparam In The input type of the app for the visit method.
  * @tparam Out The output type of the visit method.
  */
trait AppVisitor[In, Out] {

  /**
    * Visit an app.
    *
    * @param app The app that is visited.
    * @param groupId The absolute path of the group that holds the app. Eg the group `/prod/db` might
    *                hold the app `/prod/db/postgresql`.
    * @return The result of the visit.
    */
  def visit(app: In, groupId: AbsolutePathId): Out
}

/**
  * Represents a composition of two app visitors. It will call the outer visit method on the result
  * of the inner visit call.
  *
  * @param inner The [[AppVisitor]] that is first visited.
  * @param outer The [[AppVisitor]] that will visit the result of the inner visit.
  * @tparam InnterIn The input type to the inner visit.
  * @tparam InnterOut The result type of the inner visit.
  * @tparam OuterOut The result type of the outer visit.
  */
case class AppVisitorCompose[InnterIn, InnterOut, OuterOut](inner: AppVisitor[InnterIn, InnterOut], outer: AppVisitor[InnterOut, OuterOut]) extends AppVisitor[InnterIn, OuterOut] {

  override def visit(app: InnterIn, groupId: AbsolutePathId): OuterOut = outer.visit(inner.visit(app, groupId), groupId)
}

case class ValidateOrThrowVisitor[GroupOut, AppOut]() extends GroupUpdateVisitor[Either[Failure, GroupOut], Either[Failure, AppOut], AppOut, GroupOut] {

  object AppValidateOrThrowVisitor extends AppVisitor[Either[Failure, AppOut], AppOut] {
    override def visit(app: Either[Failure, AppOut], groupId: AbsolutePathId): AppOut = app match {
      case Left(f) => throw ValidationFailedException(???, f)
      case Right(r) => r
    }
  }

  override def visit(thisGroup: Either[Failure, GroupOut]): GroupOut = thisGroup match {
    case Left(f) => throw ValidationFailedException(???, f)
    case Right(r) => r
  }

  override def appVisitor(): AppVisitor[Either[Failure, AppOut], AppOut] = AppValidateOrThrowVisitor

  override def childGroupVisitor(): GroupUpdateVisitor[Either[Failure, GroupOut], Either[Failure, AppOut], AppOut, GroupOut] = this

  override def done(base: AbsolutePathId, thisGroup: GroupOut, children: Option[Vector[GroupOut]], apps: Option[Vector[AppOut]]): GroupOut = thisGroup
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
  def dispatch[AppOut, GroupOut](groupUpdate: raml.GroupUpdate, base: AbsolutePathId, visitor: GroupUpdateVisitor[raml.GroupUpdate, raml.App, AppOut, GroupOut]): GroupOut = {
    val visitedGroup: GroupOut = visitor.visit(groupUpdate)

    // Visit each child group.
    val childGroupVisitor = visitor.childGroupVisitor()
    val visitedChildren: Option[Vector[GroupOut]] = groupUpdate.groups.map(_.toVector.map { childGroup =>
      val absoluteChildGroupPath = PathId(childGroup.id.get).canonicalPath(base)
      dispatch(childGroup, absoluteChildGroupPath, childGroupVisitor)
    })

    // Visit each app.
    val appVisitor = visitor.appVisitor()
    val visitedApps: Option[Vector[AppOut]] = groupUpdate.apps.map(_.toVector.map { app =>
      appVisitor.visit(app, base)
    })

    visitor.done(base, visitedGroup, visitedChildren, visitedApps)
  }
}

