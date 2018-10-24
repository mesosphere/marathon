package mesosphere.marathon.core.leadership;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)

/**
 * If an actor doesn't start until Marathon is the leader, mark this message as safe to defer until then.
 *
 * This exists to work around some deficiencies with the WhenLeaderActor mechanism that exists right now. See MARATHON-8486 about the removal.
 */
public @interface LeaderDeferrable {}
