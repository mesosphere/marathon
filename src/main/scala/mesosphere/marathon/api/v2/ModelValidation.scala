package mesosphere.marathon.api.v2

import java.lang.annotation.ElementType
import javax.validation.{ ConstraintViolation, ConstraintViolationException, Validation }

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.{ PathId, ScalingStrategy }
import org.hibernate.validator.internal.engine.ConstraintViolationImpl
import org.hibernate.validator.internal.engine.path.PathImpl

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
  * Bean validation helper trait.
  * TODO: we should not use bean validation any longer.
  */
trait BeanValidation {

  val validator = Validation.buildDefaultValidatorFactory().getValidator

  def violation[Bean, Prop](bean: Bean, prop: Prop, path: String, msg: String)(implicit ct: ClassTag[Bean]): ConstraintViolation[Bean] = {
    ConstraintViolationImpl.forParameterValidation[Bean](
      msg, msg, ct.runtimeClass.asInstanceOf[Class[Bean]], bean, prop, prop,
      PathImpl.createPathFromString(path),
      null, ElementType.FIELD, Array())
  }

  def withPath[T](bean: T, e: ConstraintViolation[_], path: String)(implicit ct: ClassTag[T]): ConstraintViolation[T] = {
    ConstraintViolationImpl.forParameterValidation[T](
      e.getMessageTemplate, e.getMessage, ct.runtimeClass.asInstanceOf[Class[T]], bean, e.getLeafBean, e.getInvalidValue,
      PathImpl.createPathFromString(path + e.getPropertyPath),
      e.getConstraintDescriptor, ElementType.FIELD, e.getExecutableParameters)
  }

  def notSet[Bean, Prop](bean: Bean, path: String)(implicit ct: ClassTag[Bean]) = {
    violation(bean, null, path, "Property missing which is mandatory")
  }

  def defined[Bean, Prop](bean: Bean, opt: Option[Prop], path: String,
                          fn: (Bean, Prop, String) => Iterable[ConstraintViolation[Bean]],
                          mandatory: Boolean = false)(implicit ct: ClassTag[Bean]): Iterable[ConstraintViolation[Bean]] = {
    opt match {
      case Some(t)           => fn(bean, t, path)
      case None if mandatory => List(notSet(bean, path))
      case _                 => Nil
    }
  }

  def validate[T](bean: T, path: String, violationSets: Iterable[ConstraintViolation[_]]*)(implicit ct: ClassTag[T]): Iterable[ConstraintViolation[T]] = {
    val beanErrors = validator.validate(bean).asScala
    val result = beanErrors ++ violationSets.flatMap(identity)
    result.map(withPath(bean, _, path))
  }

  def requireValid[T](errors: Iterable[ConstraintViolation[T]]) {
    if (errors.nonEmpty) throw new ConstraintViolationException("Bean is not valid", errors.toSet.asJava)
  }
}

/**
  * Specific validation helper for specific model classes.
  */
trait ModelValidation extends BeanValidation {

  def checkGroup(group: GroupUpdate, path: String = ""): Iterable[ConstraintViolation[GroupUpdate]] = {
    validate(group, path,
      defined(group, group.id, "id", (b: GroupUpdate, p: PathId, i: String) => idErrors(b, p, i), mandatory = true),
      group.apps.map(checkApps(_, path + "apps")).getOrElse(Nil),
      group.groups.map(checkGroups(_, path + "groups")).getOrElse(Nil)
    )
  }

  def checkGroups(groups: Iterable[GroupUpdate], path: String = "res") = {
    groups.zipWithIndex.flatMap{ case (group, pos) => checkGroup(group, s"$path[$pos].") }
  }

  def checkUpdates(apps: Iterable[AppUpdate], path: String = "res") = {
    apps.zipWithIndex.flatMap{ case (app, pos) => checkUpdate(app, s"$path[$pos].") }
  }

  def checkApps(apps: Iterable[AppDefinition], path: String = "res") = {
    apps.zipWithIndex.flatMap{ case (app, pos) => checkApp(app, s"$path[$pos].") }
  }

  def checkUpdate(app: AppUpdate, path: String = "", needsId: Boolean = false) = {
    validate(app, path,
      defined(app, app.id, "id", (b: AppUpdate, p: PathId, i: String) => idErrors(b, p, i), needsId),
      defined(app, app.scalingStrategy, "scalingStrategy", (b: AppUpdate, p: ScalingStrategy, i: String) => healthErrors(b, p, i)),
      defined(app, app.dependencies, "dependencies", (b: AppUpdate, p: Set[PathId], i: String) => dependencyErrors(b, p, i))
    )
  }

  def checkApp(app: AppDefinition, path: String = "") = {
    validate(app, path,
      idErrors(app, app.id, "id"),
      healthErrors(app, app.scalingStrategy, "scalingStrategy"),
      dependencyErrors(app, app.dependencies, "dependencies")
    )
  }

  def idErrors[T](t: T, id: PathId, path: String)(implicit ct: ClassTag[T]): List[ConstraintViolation[T]] = {
    val p = "^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])$".r
    val valid = id.path.forall(p.pattern.matcher(_).matches())
    if (!valid) List(violation(t, id, path, s"contains invalid characters. Allowed characters: [a-z0-9]")) else Nil
  }

  def dependencyErrors[T](t: T, set: Set[PathId], path: String)(implicit ct: ClassTag[T]) = {
    set.zipWithIndex.flatMap{ case (id, pos) => idErrors(t, id, s"$path[$pos]") }
  }

  def healthErrors[T](t: T, scalingStrategy: ScalingStrategy, path: String)(implicit ct: ClassTag[T]) = {
    val capacityErrors = {
      if (scalingStrategy.minimumHealthCapacity < 0) Some("is less than 0")
      else if (scalingStrategy.minimumHealthCapacity > 1) Some("is greater than 1")
      else None
    } map { violation(t, scalingStrategy, path + ".minimumHealthCapacity", _) }
    val scalingErrors = scalingStrategy.maximumRunningFactor.collect {
      case x if x < 1                                      => "is less than 1"
      case x if x <= scalingStrategy.minimumHealthCapacity => "is less than or equal to minimumHealthCapacity"
    } map { violation(t, scalingStrategy, path + ".maximumRunningFactor", _) }
    capacityErrors ++ scalingErrors
  }
}
