package mesosphere.marathon
package raml

trait ResourcesConversion {
  implicit val resourcesRamlReader: Reads[Resources, state.Resources] = Reads { raml =>
    state.Resources(raml.cpus, raml.mem, raml.disk, raml.gpus)
  }
  implicit val resourcesRamlRwiter: Writes[state.Resources, Resources] = Writes { res =>
    Resources(res.cpus, res.mem, res.disk, res.gpus)
  }
}

object ResourcesConversion extends ResourcesConversion
