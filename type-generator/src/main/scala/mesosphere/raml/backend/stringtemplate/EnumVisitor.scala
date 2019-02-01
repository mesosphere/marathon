package mesosphere.raml.backend.stringtemplate

import java.io.File

import com.typesafe.scalalogging.StrictLogging
import liqp.Template
import liqp.filters.Filter
import mesosphere.raml.ir.EnumT

import scala.collection.JavaConverters._

object EnumVisitor extends StrictLogging {

  object UpperCaseFilter extends Filter("upperCase") {

    override def apply(value: Any, params: Object*): Object= {
      value match {
        case s: String => s.toUpperCase
        case it: java.lang.Iterable[String] => it.asScala.map(_.toUpperCase).asJava
        case _ => throw new IllegalArgumentException(s"Value $value is not a String.")
      }
    }
  }
  object LowerCaseFilter extends Filter("lowerCase") {

    override def apply(value: Any, params: Object*): Object= {
      value match {
        case s: String => s.toLowerCase
        case it: java.lang.Iterable[String] => it.asScala.map(_.toLowerCase).asJava
        case _ => throw new IllegalArgumentException(s"Value $value is not a String.")
      }
    }
  }
  Filter.registerFilter(UpperCaseFilter)
  Filter.registerFilter(LowerCaseFilter)

  def visit(enumT: EnumT): Seq[String] = {

    val EnumT(name, values, default, comments) = enumT

    val classLoader: ClassLoader = getClass.getClassLoader
    val file: File = new File(classLoader.getResource("templates/enum.liquid").getFile)
    val template = Template.parse(file)
    val out : String = template.render(true, "enum", enumT)

    Seq(out)
  }
}
