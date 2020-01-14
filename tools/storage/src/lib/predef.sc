import $file.`load-jar`
import $file.bindings
import $file.dsl
import $file.helpers
val storageInstance = new bindings.MarathonStorage()
import storageInstance._
val dslInstance = new dsl.DSL(storageInstance.unverifiedModule)
import helpers.Helpers._
import dslInstance._
