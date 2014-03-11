package scala.mesosphere.marathon.api.v1;

import mesosphere.marathon.api.v1.AppDefinitionValidator;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.*;

@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = { AppDefinitionValidator.class })
@Documented
public @interface ValidPortIndex {

    String message() default "ValidPassengerCount.message}";

    Class<?>[] groups() default { };

    Class<? extends Payload>[] payload() default { };
}