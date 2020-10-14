package mesosphere.marathon

import org.rogach.scallop.{ScallopConf, ScallopOption}

object ScallopHelper {

  /**
    * Return all defined options for a Scallop config; uses Java reflection to search for and invoke the
    * appropriate methods
    *
    * @return List of all found ScallopOptions
    */
  def scallopOptions(o: ScallopConf): Seq[ScallopOption[_]] = {
    o.getClass.getMethods.iterator.collect {
      case method if method.getParameterCount == 0 && method.getReturnType == classOf[ScallopOption[_]] =>
        method.invoke(o).asInstanceOf[ScallopOption[_]]
    }.toList
  }
}
