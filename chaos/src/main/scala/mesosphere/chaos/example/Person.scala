package mesosphere.chaos.example

import org.hibernate.validator.constraints.{ NotEmpty, Range }

class Person {
  @NotEmpty
  var name: String = ""
  @Range(min = 0, max = 150)
  var age: Int = 0

  override def toString = {
    s"Person($name, $age)"
  }
}
