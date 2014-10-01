package mesosphere.marathon.api.validation;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.*;

@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AppDefinitionValidator.class)
@Documented
public @interface ValidAppDefinition {
  String message() default "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'.";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
