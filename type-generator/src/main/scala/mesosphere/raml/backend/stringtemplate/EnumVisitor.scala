package mesosphere.raml.backend.stringtemplate

import java.util.Locale

import com.typesafe.scalalogging.StrictLogging
import mesosphere.raml.ir.EnumT
import org.clapper.scalasti.{AttributeRenderer, ST, STGroup, STGroupFile}

class UpperCase extends AttributeRenderer[String] {
  override def toString(o: String, formatString: String, locale: Locale): String = o.toUpperCase(locale)
}

object EnumVisitor extends StrictLogging {

  def visit(enumT: EnumT): Seq[String] = {

    val EnumT(name, values, default, comments) = enumT

    val templates: STGroup = STGroupFile("templates/enum.stg")

    //val enumTemplate: ST = templates.instanceOf("base").get
    //val out = enumTemplate.add("enum", enumT).render().get

    templates.registerRenderer[String](new UpperCase)
    val enumTemplate: ST = templates.instanceOf("foo").get
    val out = enumTemplate.add("items", enumT.sortedValues).render().get

    Seq(out)
  }
}
