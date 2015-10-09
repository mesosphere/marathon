package mesosphere.marathon.api.v2

import java.net.{ HttpURLConnection, URL, URLConnection }
import javax.validation.ConstraintViolation

import mesosphere.marathon.api.v2.BeanValidation._
import mesosphere.marathon.api.v2.json.{ V2AppDefinition, V2AppUpdate, V2Group, V2GroupUpdate }
import mesosphere.marathon.state._

import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

/**
  * Specific validation helper for specific model classes.
  */
object ModelValidation {

  //scalastyle:off null

  // TODO: Re-implement this method on it's own terms
  def checkGroup(
    group: Group,
    path: String,
    parent: PathId,
    maximum: Int): Iterable[ConstraintViolation[V2Group]] = {

    val v2Group = V2Group(group)
    val maximumApps = if (group.transitiveApps.size > maximum) {
      Seq(violation(v2Group, "apps", "", s"This version of Marathon can only handle up to $maximum Apps!"))
    }
    else Seq.empty[ConstraintViolation[V2Group]]

    checkGroup(v2Group, path, parent) ++ maximumApps
  }

  def checkGroup(
    group: V2Group,
    path: String = "",
    parent: PathId = PathId.empty): Iterable[ConstraintViolation[V2Group]] = {
    val base = group.id.canonicalPath(parent)
    validate(group,
      idErrors(group, base, group.id, "id"),
      checkPath(group, parent, group.id, path + "id"),
      checkApps(group.apps, path + "apps", base),
      checkGroups(group.groups, path + "groups", base),
      noAppsAndGroupsWithSameName(group, path + "apps", group.apps, group.groups),
      noCyclicDependencies(group, path + "dependencies")
    )
  }

  def checkGroupUpdate(
    group: V2GroupUpdate,
    needsId: Boolean,
    path: String = "",
    parent: PathId = PathId.empty): Iterable[ConstraintViolation[V2GroupUpdate]] = {
    if (group == null) {
      Seq(violation(group, null, "", "Given group is empty!"))
    }
    else if (group.version.isDefined || group.scaleBy.isDefined) {
      validate(group,
        defined(
          group,
          group.version,
          "version",
          (b: V2GroupUpdate, t: Timestamp, i: String) => hasOnlyOneDefinedOption(b, t, i),
          mandatory = false
        ),
        defined(
          group,
          group.scaleBy,
          "scaleBy",
          (b: V2GroupUpdate, t: Double, i: String) => hasOnlyOneDefinedOption(b, t, i),
          mandatory = false
        )
      )
    }
    else {
      val base = group.id.map(_.canonicalPath(parent)).getOrElse(parent)
      validate(group,
        defined(
          group,
          group.id,
          "id",
          (b: V2GroupUpdate, p: PathId, i: String) => idErrors(b, group.groupId.canonicalPath(parent), p, i),
          mandatory = needsId
        ),
        group.id.map(checkPath(group, parent, _, path + "id")).getOrElse(Nil),
        group.apps.map(checkApps(_, path + "apps", base)).getOrElse(Nil),
        group.groups.map(checkGroupUpdates(_, path + "groups", base)).getOrElse(Nil)
      )
    }
  }

  private[this] def hasOnlyOneDefinedOption[A <: Product: ClassTag, B](product: A, prop: B, path: String) = {
    val definedOptionsCount = product.productIterator.count {
      case Some(_) => true
      case _       => false
    }
    isTrue(product, prop, path, "not allowed in conjunction with other properties", definedOptionsCount == 1)
  }

  def noAppsAndGroupsWithSameName[T](
    t: T,
    path: String,
    apps: Set[V2AppDefinition],
    groups: Set[V2Group])(implicit ct: ClassTag[T]): Iterable[ConstraintViolation[_]] = {
    val groupIds = groups.map(_.id)
    val clashingIds = apps.map(_.id).intersect(groupIds)
    isTrue(
      t,
      apps,
      path,
      s"Groups and Applications may not have the same identifier: ${clashingIds.mkString(", ")}",
      clashingIds.isEmpty
    )
  }

  def noCyclicDependencies(
    group: V2Group,
    path: String): Iterable[ConstraintViolation[V2Group]] = {
    isTrue(
      group,
      group.dependencies,
      path,
      "Dependency graph has cyclic dependencies",
      group.toGroup().hasNonCyclicDependencies)
  }

  def checkGroupUpdates(
    groups: Iterable[V2GroupUpdate],
    path: String = "res",
    parent: PathId = PathId.empty): Iterable[ConstraintViolation[V2GroupUpdate]] =
    groups.zipWithIndex.flatMap {
      case (group, pos) =>
        checkGroupUpdate(group, needsId = true, s"$path[$pos].", parent)
    }

  def checkGroups(
    groups: Iterable[V2Group],
    path: String = "res",
    parent: PathId = PathId.empty): Iterable[ConstraintViolation[V2Group]] =
    groups.zipWithIndex.flatMap {
      case (group, pos) =>
        checkGroup(group, s"$path[$pos].", parent)
    }

  def checkUpdates(
    apps: Iterable[V2AppUpdate],
    path: String = "res"): Iterable[ConstraintViolation[V2AppUpdate]] =
    apps.zipWithIndex.flatMap {
      case (app, pos) =>
        checkUpdate(app, s"$path[$pos].")
    }

  def checkPath[T: ClassTag](
    t: T,
    parent: PathId,
    child: PathId,
    path: String): Iterable[ConstraintViolation[T]] = {
    val isParent = child.canonicalPath(parent).parent == parent
    if (parent != PathId.empty && !isParent)
      List(violation(t, child, path, s"identifier $child is not child of $parent. Hint: use relative paths."))
    else Nil
  }

  def checkApps(
    apps: Iterable[V2AppDefinition],
    path: String = "res",
    parent: PathId = PathId.empty): Iterable[ConstraintViolation[V2AppDefinition]] =
    apps.zipWithIndex.flatMap {
      case (app, pos) =>
        checkAppConstraints(app, parent, s"$path[$pos].")
    }

  def checkUpdate(
    app: V2AppUpdate,
    path: String = "",
    needsId: Boolean = false): Iterable[ConstraintViolation[V2AppUpdate]] = {
    validate(app,
      defined(
        app,
        app.id,
        "id",
        (b: V2AppUpdate, p: PathId, i: String) => idErrors(b, PathId.empty, p, i),
        needsId
      ),
      defined(
        app,
        app.upgradeStrategy,
        "upgradeStrategy",
        (b: V2AppUpdate, p: UpgradeStrategy, i: String) => upgradeStrategyErrors(b, p, i)
      ),
      defined(
        app,
        app.dependencies,
        "dependencies",
        (b: V2AppUpdate, p: Set[PathId], i: String) => dependencyErrors(b, PathId.empty, p, i)
      ),
      defined(
        app,
        app.storeUrls,
        "storeUrls",
        (b: V2AppUpdate, p: Seq[String], i: String) => urlsCanBeResolved(b, p, i)
      )
    )
  }

  def checkAppConstraints(app: V2AppDefinition, parent: PathId,
                          path: String = ""): Iterable[ConstraintViolation[V2AppDefinition]] =
    validate(app,
      idErrors(app, parent, app.id, path + "id"),
      checkPath(app, parent, app.id, path + "id"),
      upgradeStrategyErrors(app, app.upgradeStrategy, path + "upgradeStrategy"),
      dependencyErrors(app, parent, app.dependencies, path + "dependencies"),
      urlsCanBeResolved(app, app.storeUrls, path + "storeUrls")
    )

  def urlsCanBeResolved[T: ClassTag](t: T, urls: Seq[String], path: String): Iterable[ConstraintViolation[T]] = {
    def urlIsValid(url: String): Boolean = Try {
      new URL(url).openConnection() match {
        case http: HttpURLConnection =>
          http.setRequestMethod("HEAD")
          http.getResponseCode == HttpURLConnection.HTTP_OK
        case other: URLConnection =>
          other.getInputStream
          true //if we come here, we could read the stream
      }
    }.getOrElse(false)

    urls.toList
      .zipWithIndex
      .collect {
        case (url, pos) if !urlIsValid(url) =>
          violation(t, urls, s"$path[$pos]", s"Can not resolve url $url")
      }
  }

  def idErrors[T: ClassTag](t: T, base: PathId, id: PathId, path: String): Iterable[ConstraintViolation[T]] = {
    val p = "^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])|(\\.|\\.\\.)$".r
    val valid = id.path.forall(p.pattern.matcher(_).matches())
    val errors =
      if (!valid)
        List(
          violation(
            t,
            id,
            path,
            "path contains invalid characters (allowed: lowercase letters, digits, hyphens, \".\", \"..\")"
          )
        )
      else Nil

    Try(id.canonicalPath(base)) match {
      case Success(_) => errors
      case Failure(_) => violation(t, id, path, s"canonical path can not be computed for $id") :: errors
    }

    errors
  }

  def dependencyErrors[T: ClassTag](
    t: T,
    base: PathId,
    set: Set[PathId],
    path: String): Iterable[ConstraintViolation[T]] =
    set.zipWithIndex.flatMap{ case (id, pos) => idErrors(t, base, id, s"$path[$pos]") }

  def upgradeStrategyErrors[T: ClassTag](
    t: T,
    upgradeStrategy: UpgradeStrategy,
    path: String): Iterable[ConstraintViolation[T]] = {
    if (upgradeStrategy.minimumHealthCapacity < 0) Some("is less than 0")
    else if (upgradeStrategy.minimumHealthCapacity > 1) Some("is greater than 1")
    else None
  }.map { violation(t, upgradeStrategy, path + ".minimumHealthCapacity", _) }
    .orElse({
      if (upgradeStrategy.maximumOverCapacity < 0) Some("is less than 0")
      else if (upgradeStrategy.maximumOverCapacity > 1) Some("is greater than 1")
      else None
    }.map { violation(t, upgradeStrategy, path + ".maximumOverCapacity", _) })

  /**
    * Returns a non-empty list of validation messages if the given app definition
    * will conflict with existing apps.
    */
  def checkAppConflicts(app: AppDefinition, root: Group): Seq[String] = {
    app.containerServicePorts.toSeq.flatMap { servicePorts =>
      checkServicePortConflicts(app.id, servicePorts, root)
    }
  }

  /**
    * Returns a non-empty list of validations messages if the given app definition has service ports
    * that will conflict with service ports in other applications.
    *
    * Does not compare the app definition's service ports with the same deployed app's service ports, as app updates
    * may simply restate the existing service ports.
    */
  private def checkServicePortConflicts(appId: PathId, requestedServicePorts: Seq[Int],
                                        root: Group): Seq[String] = {

    for {
      existingApp <- root.transitiveApps.toList
      if existingApp.id != appId // in case of an update, do not compare the app against itself
      existingServicePort <- existingApp.portMappings.toList.flatten.map(_.servicePort)
      if existingServicePort != 0 // ignore zero ports, which will be chosen at random
      if requestedServicePorts contains existingServicePort
    } yield s"Requested service port $existingServicePort conflicts with a service port in app ${existingApp.id}"
  }

}
