package mesosphere.chaos.example

import com.google.inject.Scopes
import mesosphere.chaos.http.RestModule

class ExampleRestModule extends RestModule {

  protected override def configureServlets() {
    super.configureServlets()

    bind(classOf[ExampleResource]).in(Scopes.SINGLETON)
  }

}
