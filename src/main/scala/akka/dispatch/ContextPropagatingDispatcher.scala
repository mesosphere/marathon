package akka.dispatch

import java.util

import mesosphere.marathon.core.async.{ Context, propagateContext }
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{ Around, Aspect }
import org.slf4j.MDC

// Note: we have to override behavior that is private to akka, so this has to be in the akka.dispatch package.

private class ContextWrapper(val invocation: Envelope, val context: Map[Context.ContextName[_], Any], val mdc: Option[util.Map[String, String]])
private object ContextWrapper {
  def unapply(cw: ContextWrapper): Option[(Envelope, Map[Context.ContextName[_], Any], Option[util.Map[String, String]])] =
    Some((cw.invocation, cw.context, cw.mdc))
}

/**
  * Aspect that wraps and unwraps Akka Messages
  * so that the Context/MDC are around the message.
  */
@Aspect
private class WeaveActorReceive {
  @SuppressWarnings(Array("MethodReturningAny"))
  @Around("execution(* akka.actor..ActorCell+.invoke(..)) && args(envelope)")
  def contextInvoke(pjp: ProceedingJoinPoint, envelope: Envelope): Any = {
    envelope match {
      case Envelope(ContextWrapper(originalEnvelope, context, mdc), _) =>
        propagateContext(context, mdc)(pjp.proceed(Array(originalEnvelope)))
      case _ =>
        pjp.proceed(Array(envelope))
    }
  }

  // Unstarted actors use a chain of sendMessage, so we need to wrap only if we haven't already.
  @SuppressWarnings(Array("MethodReturningAny"))
  @Around("execution(* akka.actor..Cell+.sendMessage(..)) && args(envelope)")
  def sendMessage(pjp: ProceedingJoinPoint, envelope: Envelope): Any = {
    envelope match {
      case Envelope(ContextWrapper(_, _, _), _) =>
        // don't re-wrap
        pjp.proceed(Array(envelope))
      case _ =>
        pjp.proceed(Array(Envelope(new ContextWrapper(envelope, Context.copy(), Option(MDC.getCopyOfContextMap)), envelope.sender)))
    }
  }
}

