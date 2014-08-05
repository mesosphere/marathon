package mesosphere.marathon.api

import java.lang.annotation.ElementType
import javax.validation.{ ConstraintViolation, ConstraintViolationException, Validation }
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

import org.hibernate.validator.internal.engine.ConstraintViolationImpl
import org.hibernate.validator.internal.engine.path.PathImpl

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

  def isTrue[Bean, Prop](bean: Bean, prop: Prop, path: String, message: String, assertion: => Boolean)(implicit ct: ClassTag[Bean]) = {
    if (!assertion) List(violation(bean, prop, path, message)) else Nil
  }

  def validate[T](bean: T, violationSets: Iterable[ConstraintViolation[_]]*)(implicit ct: ClassTag[T]): Iterable[ConstraintViolation[T]] = {
    val beanErrors = validator.validate(bean).asScala
    val result = beanErrors ++ violationSets.flatMap(identity)
    result.map(withPath(bean, _, ""))
  }

  def requireValid[T](errors: Iterable[ConstraintViolation[T]]) {
    if (errors.nonEmpty) throw new ConstraintViolationException("Bean is not valid", errors.toSet.asJava)
  }
}

