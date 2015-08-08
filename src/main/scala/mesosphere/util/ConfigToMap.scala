package mesosphere.util

import org.rogach.scallop.ScallopConf

object ConfigToMap {

  def convertToMap(config: ScallopConf): Map[String, Option[Any]] = {
    config.builder.opts.map(
      o => o.name -> config.builder.get(o.name)(o.converter.tag)
    ).toMap
  }
}
