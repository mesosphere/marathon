package mesosphere.marathon

import org.rogach.scallop.{ScallopConf, ScallopOption}

object ScallopHelper {
  def scallopOptions(o: ScallopConf): Seq[ScallopOption[_]] = {
    o.getClass.getMethods.iterator.collect {
      case method if method.getParameterCount == 0 && method.getReturnType == classOf[ScallopOption[_]] =>
        method.invoke(o).asInstanceOf[ScallopOption[_]]
    }.toList
  }
}
