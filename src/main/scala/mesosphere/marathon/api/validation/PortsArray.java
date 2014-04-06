package mesosphere.marathon.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.validation.Constraint;
import javax.validation.Payload;

@Target({
	ElementType.METHOD,
	ElementType.FIELD,
	ElementType.ANNOTATION_TYPE,
	ElementType.CONSTRUCTOR,
	ElementType.PARAMETER
})
@Documented
@Constraint(validatedBy = PortsArrayValidator.class)
@Retention(RetentionPolicy.RUNTIME)
public @interface PortsArray {
	String message() default "Elements must be unique";
	Class<?>[] groups() default {};
	Class<? extends Payload>[] payload() default {};
}
