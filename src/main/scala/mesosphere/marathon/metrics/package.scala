package mesosphere.marathon

import org.aopalliance.intercept.MethodInvocation

package object metrics {
  def className(klass: Class[_]): String = {
    if (klass.getName.contains("$EnhancerByGuice$")) klass.getSuperclass.getName else klass.getName
  }

  def name(prefix: MetricPrefix, klass: Class[_], name: String): String = {
    s"${prefix.name}.${className(klass)}.$name".replace('$', '.').replaceAll("""\.+""", ".")
  }

  def name(prefix: MetricPrefix, in: MethodInvocation): String = {
    name(prefix, in.getThis.getClass, in.getMethod.getName)
  }
}
