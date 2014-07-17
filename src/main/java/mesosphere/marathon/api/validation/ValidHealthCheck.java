package mesosphere.marathon.api.validation;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.*;

@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = HealthCheckValidator.class)
@Documented
public @interface ValidHealthCheck {
     String message() default "Health check protocol must match supplied fields.";
     Class<?>[] groups() default {};
     Class<? extends Payload>[] payload() default {};
}
