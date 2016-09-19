package mesosphere.marathon;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@org.scalatest.TagAnnotation
@Retention(RUNTIME)
@Inherited
@Target({METHOD, TYPE})
public @interface IntegrationTest {
}
