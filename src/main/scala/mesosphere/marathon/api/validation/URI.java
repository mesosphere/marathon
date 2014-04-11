package mesosphere.marathon.api.validation;

import javax.validation.*;
import java.lang.annotation.*;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Bean field validation annotation to mark valid uri's
 */
@SuppressWarnings("UnusedDeclaration")
@Target({ METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER })
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = URIValidator.class)
@ReportAsSingleViolation
public @interface URI {
	String message() default "No valid URI";
	Class<?>[] groups() default {};
	Class<? extends Payload>[] payload() default {};
}
