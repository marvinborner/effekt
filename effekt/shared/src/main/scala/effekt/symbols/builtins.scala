package effekt.symbols

import effekt.source.ModuleDecl
import kiama.util.StringSource

/**
 * The symbols, which are built into Effekt
 */
object builtins {

  // a dummy module for built in types. Can be dropped, once they are
  // defined in the prelude
  lazy val prelude = Module(ModuleDecl("effekt", Nil, Nil), StringSource("", "effekt.effekt"))

  private def name(s: String) = Name.qualified(s, prelude)

  val TInt = BuiltinType(name("Int"), Nil)
  val TBoolean = BuiltinType(name("Boolean"), Nil)
  val TUnit = BuiltinType(name("Unit"), Nil)
  val TString = BuiltinType(name("String"), Nil)
  val TDouble = BuiltinType(name("Double"), Nil)

  val TTop = BuiltinType(name("⊤"), Nil)
  val TBottom = BuiltinType(name("⊥"), Nil)

  val IOEffect = BuiltinEffect(name("IO"), Nil)
  val IOCapability = BuiltinCapability(IOEffect)

  val ControlEffect = BuiltinEffect(name("Control"), Nil)
  val ControlCapablity = BuiltinCapability(ControlEffect)

  val rootTypes: Map[String, TypeSymbol] = Map(
    "Int" -> TInt,
    "Boolean" -> TBoolean,
    "Unit" -> TUnit,
    "String" -> TString,
    "Double" -> TDouble,
    "IO" -> IOEffect
  )

  // it is a set, because terms can be overloaded...
  val rootTerms: Map[String, Set[TermSymbol]] = Map(
    "io" -> Set(IOCapability)
  )
}
