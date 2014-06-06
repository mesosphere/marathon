package mesosphere.marathon.api.validation;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.*;

@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PortIndicesValidator.class)
@Documented
public @interface PortIndices {
     String message() default "Port indices must address an element of this app's ports array.";
     Class<?>[] groups() default {};
     Class<? extends Payload>[] payload() default { };
}
