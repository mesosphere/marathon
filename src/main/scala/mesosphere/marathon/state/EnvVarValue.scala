package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.plugin

sealed trait EnvVarValue extends plugin.EnvVarValue {
  val valueValidator = new Validator[EnvVarValue] {
    override def apply(v: EnvVarValue) =
      v match {
        case s: EnvVarString => validate(s)(EnvVarString.valueValidator)
        case r: EnvVarSecretRef => validate(r)(EnvVarSecretRef.valueValidator)
      }
  }
}

sealed trait EnvVarRef extends EnvVarValue {
  val appValidator: Validator[AppDefinition]
}

case class EnvVarString(value: String) extends EnvVarValue with plugin.EnvVarString

case class EnvVarSecretRef(secret: String) extends EnvVarRef with plugin.EnvVarSecretRef {
  override lazy val appValidator = EnvVarSecretRef.appValidator
}

object EnvVarValue {
  def apply(m: Map[String, String]): Map[String, EnvVarValue] =
    m.map { case (k, v) => k -> v.toEnvVar }

  implicit class FromString(val string: String) extends AnyVal {
    def toEnvVar: EnvVarValue = EnvVarString(string)
  }

  // forward implicit validation to the internal API we've defined
  implicit def valueValidator: Validator[EnvVarValue] = validator[EnvVarValue] { v =>
    v is valid(v.valueValidator)
  }

  /** @return a validator that checks the validity of a container given the related secrets */
  def validApp(): Validator[AppDefinition] = new Validator[AppDefinition] {
    def apply(app: AppDefinition) = {
      val refValidators = app.env.collect{ case (s: String, sr: EnvVarRef) => sr.appValidator }.toSet
      refValidators.map(validate(app)(_)).fold(Success)(_ and _)
    }
  }

  def envValidator: Validator[Map[String, EnvVarValue]] = new Validator[Map[String, EnvVarValue]] {
    override def apply(env: Map[String, EnvVarValue]): Result = {
      env.toSeq.map(validate(_)(envEntryValidator)).fold(Success)(_ and _)
    }
  }

  def envEntryValidator: Validator[Tuple2[String, EnvVarValue]] = validator[Tuple2[String, EnvVarValue]] { t =>
    t._1 as s"(${t._1})" is notEmpty
    t._2 as s"(${t._1})" is valid
  }
}

object EnvVarString {
  import com.wix.accord.combinators.NilValidator
  lazy val valueValidator = new NilValidator[EnvVarString]
}

object EnvVarSecretRef {
  import mesosphere.marathon.api.v2.Validation._

  lazy val valueValidator = validator[EnvVarSecretRef] { ref =>
    ref.secret is notEmpty
  }

  lazy val appValidator = new Validator[AppDefinition] {
    override def apply(app: AppDefinition): Result = {
      val envSecrets = app.env.collect{ case (s: String, sr: EnvVarSecretRef) => (s, sr) }.toSet
      val zipped = List.fill(envSecrets.size)(app).zip(envSecrets.toSeq).map { case (a, (b, c)) => (a, b, c) }
      zipped.map(validate(_)(tupleValidator)).fold(Success)(_ and _)
    }
  }

  lazy val tupleValidator: Validator[(AppDefinition, String, EnvVarSecretRef)] = {
    val ifSecretIsDefined = isTrue[(AppDefinition, String, EnvVarSecretRef)](
      (t: (AppDefinition, String, EnvVarSecretRef)) => s"references an undefined secret named '${t._3.secret}'"
    ) { (t: (AppDefinition, String, EnvVarSecretRef)) => t._1.secrets.keySet.exists(_ == t._3.secret) }

    validator[(AppDefinition, String, EnvVarSecretRef)] { t =>
      t as s"env(${t._2})" is valid(ifSecretIsDefined)
    }
  }
}
