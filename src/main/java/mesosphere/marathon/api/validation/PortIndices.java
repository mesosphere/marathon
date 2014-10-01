package mesosphere.marathon.api.validation;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.*;

@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PortIndicesValidator.class)
@Documented
public @interface PortIndices {
     String message() default "Health check port indices must address an element of the ports array or container port mappings.";
     Class<?>[] groups() default {};
     Class<? extends Payload>[] payload() default { };
}
