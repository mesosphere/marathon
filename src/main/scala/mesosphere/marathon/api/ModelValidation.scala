package mesosphere.marathon.api

import java.net.{ HttpURLConnection, URL }
import javax.validation.ConstraintViolation
import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.{ AppUpdate, GroupUpdate }
import mesosphere.marathon.state.{ Group, PathId, UpgradeStrategy }

/**
  * Specific validation helper for specific model classes.
  */
trait ModelValidation extends BeanValidation {

  def checkGroup(group: Group, path: String = "", parent: PathId = PathId.empty): Iterable[ConstraintViolation[Group]] = {
    val base = group.id.canonicalPath(parent)
    validate(group,
      idErrors(group, base, group.id, "id"),
      checkPath(group, parent, group.id, path + "id"),
      checkApps(group.apps, path + "apps", base),
      checkGroups(group.groups, path + "groups", base),
      noAppsAndGroups(group, path + "apps", group.apps, group.groups),
      noCyclicDependencies(group, path + "dependencies")
    )
  }

  def checkGroupUpdate(group: GroupUpdate, needsId: Boolean, path: String = "", parent: PathId = PathId.empty): Iterable[ConstraintViolation[GroupUpdate]] = {
    if ((group.version orElse group.scaleBy).isDefined) Nil else {
      val base = group.id.map(_.canonicalPath(parent)).getOrElse(parent)
      validate(group,
        defined(group, group.id, "id", (b: GroupUpdate, p: PathId, i: String) => idErrors(b, group.groupId.canonicalPath(parent), p, i), mandatory = needsId),
        group.id.map(checkPath(group, parent, _, path + "id")).getOrElse(Nil),
        group.apps.map(checkApps(_, path + "apps", base)).getOrElse(Nil),
        group.groups.map(checkGroupUpdates(_, path + "groups", base)).getOrElse(Nil),
        group.groups.flatMap(groups => group.apps.map(apps => (apps, groups))).map(ga => noAppsAndGroups(group, path + "apps", ga._1, ga._2)).getOrElse(Nil)
      )
    }
  }

  def noAppsAndGroups[T](t: T, path: String, apps: Set[AppDefinition], groups: Set[T])(implicit ct: ClassTag[T]) = {
    isTrue(t, apps, path, "Groups can define apps xor groups but can not hold both!", !(apps.nonEmpty && groups.nonEmpty))
  }

  def noCyclicDependencies(group: Group, path: String) = {
    isTrue(group, group.dependencies, path, "Dependency graph has cyclic dependencies", group.hasNonCyclicDependencies)
  }

  def checkGroupUpdates(groups: Iterable[GroupUpdate], path: String = "res", parent: PathId = PathId.empty) = {
    groups.zipWithIndex.flatMap{ case (group, pos) => checkGroupUpdate(group, needsId = true, s"$path[$pos].", parent) }
  }

  def checkGroups(groups: Iterable[Group], path: String = "res", parent: PathId = PathId.empty) = {
    groups.zipWithIndex.flatMap{ case (group, pos) => checkGroup(group, s"$path[$pos].", parent) }
  }

  def checkUpdates(apps: Iterable[AppUpdate], path: String = "res") = {
    apps.zipWithIndex.flatMap{ case (app, pos) => checkUpdate(app, s"$path[$pos].") }
  }

  def checkPath[T](t: T, parent: PathId, child: PathId, path: String)(implicit ct: ClassTag[T]) = {
    val isParent = child.canonicalPath(parent).parent == parent
    if (!isParent) List(violation(t, child, path, s"identifier $child is not child of $parent. Hint: use relative paths.")) else Nil
  }

  def checkApps(apps: Iterable[AppDefinition], path: String = "res", parent: PathId = PathId.empty) = {
    apps.zipWithIndex.flatMap{ case (app, pos) => checkApp(app, parent, s"$path[$pos].") }
  }

  def checkUpdate(app: AppUpdate, path: String = "", needsId: Boolean = false) = {
    validate(app,
      defined(app, app.id, "id", (b: AppUpdate, p: PathId, i: String) => idErrors(b, PathId.empty, p, i), needsId),
      defined(app, app.upgradeStrategy, "upgradeStrategy", (b: AppUpdate, p: UpgradeStrategy, i: String) => healthErrors(b, p, i)),
      defined(app, app.dependencies, "dependencies", (b: AppUpdate, p: Set[PathId], i: String) => dependencyErrors(b, PathId.empty, p, i)),
      defined(app, app.resolveUrls, "resolveUrls", (b: AppUpdate, p: Seq[String], i: String) => urlsCanBeResolved(b, p, i))
    )
  }

  def checkApp(app: AppDefinition, parent: PathId, path: String = "") = {
    validate(app,
      idErrors(app, parent, app.id, path + "id"),
      checkPath(app, parent, app.id, path + "id"),
      healthErrors(app, app.upgradeStrategy, path + "upgradeStrategy"),
      dependencyErrors(app, parent, app.dependencies, path + "dependencies"),
      urlsCanBeResolved(app, app.resolveUrls, path + "resolveUrls")
    )
  }

  def urlsCanBeResolved[T](t: T, urls: Seq[String], path: String)(implicit ct: ClassTag[T]): List[ConstraintViolation[T]] = {
    def urlIsValid(url: String) = Try {
      val connection = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
      connection.setRequestMethod("HEAD")
      connection.getResponseCode == HttpURLConnection.HTTP_OK
    }.getOrElse(false)
    urls.toList.zipWithIndex.collect{ case (url, pos) if !urlIsValid(url) => violation(t, urls, s"$path[$pos]", s"Can not resolve url $url") }
  }

  def idErrors[T](t: T, base: PathId, id: PathId, path: String)(implicit ct: ClassTag[T]): List[ConstraintViolation[T]] = {
    val p = "^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])|(\\.|\\.\\.)$".r
    val valid = id.path.forall(p.pattern.matcher(_).matches())
    val errors = if (!valid) List(violation(t, id, path, "contains invalid characters (allowed: [a-z0-9]* . and .. in path)")) else Nil
    Try(id.canonicalPath(base)) match {
      case Success(_) => errors
      case Failure(_) => violation(t, id, path, s"canonical path can not be computed for $id") :: errors
    }
    errors
  }

  def dependencyErrors[T](t: T, base: PathId, set: Set[PathId], path: String)(implicit ct: ClassTag[T]) = {
    set.zipWithIndex.flatMap{ case (id, pos) => idErrors(t, base, id, s"$path[$pos]") }
  }

  def healthErrors[T](t: T, upgradeStrategy: UpgradeStrategy, path: String)(implicit ct: ClassTag[T]) = {
    if (upgradeStrategy.minimumHealthCapacity < 0) Some("is less than 0")
    else if (upgradeStrategy.minimumHealthCapacity > 1) Some("is greater than 1")
    else None
  } map { violation(t, upgradeStrategy, path + ".minimumHealthCapacity", _) }
}
