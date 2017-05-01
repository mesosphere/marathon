package mesosphere.marathon;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotation for Test _Suites_ that are considered unstable.
 * A JIRA should be listed with either just the JIRA itself or the URL.
 */
@org.scalatest.TagAnnotation
@Retention(RUNTIME)
@Inherited
@Target({METHOD, TYPE})
public @interface UnstableTest {
    String issue() default "";
    String issueUrl() default "";
}
