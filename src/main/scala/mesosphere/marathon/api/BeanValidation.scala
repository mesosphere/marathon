package mesosphere.marathon.api

import java.lang.annotation.ElementType
import javax.validation.{ ConstraintViolation, ConstraintViolationException, Validation }
import scala.collection.JavaConverters._
import scala.reflect.{ classTag, ClassTag }

import org.hibernate.validator.internal.engine.ConstraintViolationImpl
import org.hibernate.validator.internal.engine.path.PathImpl

/**
  * Bean validation helper trait.
  * TODO: we should not use bean validation any longer.
  */
trait BeanValidation {

  val validator = Validation.buildDefaultValidatorFactory().getValidator

  def violation[Bean: ClassTag, Prop](
    bean: Bean,
    prop: Prop,
    path: String,
    msg: String): ConstraintViolation[Bean] = {
    ConstraintViolationImpl.forParameterValidation[Bean](
      msg, msg, classTag[Bean].runtimeClass.asInstanceOf[Class[Bean]], bean, prop, prop,
      PathImpl.createPathFromString(path),
      null, ElementType.FIELD, Array())
  }

  def withPath[T: ClassTag](bean: T, e: ConstraintViolation[_], path: String): ConstraintViolation[T] =
    ConstraintViolationImpl.forParameterValidation[T](
      e.getMessageTemplate,
      e.getMessage,
      classTag[T].runtimeClass.asInstanceOf[Class[T]],
      bean,
      e.getLeafBean,
      e.getInvalidValue,
      PathImpl.createPathFromString(path + e.getPropertyPath),
      e.getConstraintDescriptor, ElementType.FIELD, e.getExecutableParameters)

  def notSet[Bean: ClassTag, Prop](bean: Bean, path: String): ConstraintViolation[Bean] = {
    violation(bean, null, path, "Property missing which is mandatory")
  }

  def defined[Bean: ClassTag, Prop](
    bean: Bean,
    opt: Option[Prop],
    path: String,
    fn: (Bean, Prop, String) => Iterable[ConstraintViolation[Bean]],
    mandatory: Boolean = false): Iterable[ConstraintViolation[Bean]] = {
    opt match {
      case Some(t)           => fn(bean, t, path)
      case None if mandatory => List(notSet(bean, path))
      case _                 => Nil
    }
  }

  def isTrue[Bean: ClassTag, Prop](
    bean: Bean,
    prop: Prop,
    path: String,
    message: String,
    assertion: => Boolean): Seq[ConstraintViolation[Bean]] =
    if (!assertion) List(violation(bean, prop, path, message)) else Nil

  def validate[T: ClassTag](
    bean: T,
    violationSets: Iterable[ConstraintViolation[_]]*): Iterable[ConstraintViolation[T]] = {
    val beanErrors = validator.validate(bean).asScala
    val result = beanErrors ++ violationSets.flatMap(identity)
    result.map(withPath(bean, _, ""))
  }

  def requireValid[T](errors: Iterable[ConstraintViolation[T]]) {
    if (errors.nonEmpty) throw new ConstraintViolationException("Bean is not valid", errors.toSet.asJava)
  }
}

