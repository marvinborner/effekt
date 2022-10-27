package effekt
package generator
package ml

import effekt.context.Context
import effekt.lifted.*
import effekt.symbols.{Module, Symbol, Wildcard}
import effekt.util.paths.*
import kiama.output.PrettyPrinterTypes.Document

import scala.language.implicitConversions

/**
 * Lifted variant of Chez Scheme. Mostly copy-and-paste from [[ChezScheme]].
 *
 * Difficult to share the code, since core and lifted are different IRs.
 */
object ML extends Backend {

  //  // TODO we use the $then variant, for now, since the `then` variant is a macro and would
  //  // require adding it to the syntax ml.Tree
  //  def bind(binding: ml.Expr, param: MLName, body: ml.Block): ml.Expr =
  //    Builtin("$then", binding, ml.Lambda(List(param), body))

  def runMain(main: MLName): ml.Expr =
    ml.Call(Consts.run)(ml.Call(main)(Consts.here))

  /**
   * Returns [[Compiled]], containing the files that should be written to.
   */
  def compileWhole(main: CoreTransformed, dependencies: List[CoreTransformed])(using C: Context): Option[Compiled] = {
    val mainSymbol = C.checkMain(main.mod)
    val deps = dependencies.flatMap { dep => compile(dep) }

    LiftInference(main).map { lifted =>
      val mlModule = compilationUnit(mainSymbol, lifted.mod, lifted.core, deps)
      val result = ml.PrettyPrinter.pretty(ml.PrettyPrinter.toDoc(mlModule), 100)
      val mainFile = path(main.mod)
      Compiled(mainFile, Map(mainFile -> result))
    }
  }

  /**
   * Entrypoint used by the LSP server to show the compiled output
   */
  def compileSeparate(input: CoreTransformed)(using C: Context): Option[Document] =
    C.using(module = input.mod) {
      Some(ml.PrettyPrinter.format(ml.PrettyPrinter.toDoc(compile(input))))
    }

  /**
   * Compiles only the given module, does not compile dependencies
   */
  private def compile(in: CoreTransformed)(using Context): List[ml.Binding] =
    LiftInference(in).toList.flatMap { lifted => toML(lifted.core) }

  def compilationUnit(mainSymbol: Symbol, mod: Module, core: ModuleDecl, dependencies: List[ml.Binding])(implicit C: Context): ml.Toplevel = {
    val defs = toML(core)
    ml.Toplevel(dependencies ++ defs, runMain(nameRef(mainSymbol)))
  }

  /**
   * This is used for both: writing the files to and generating the `require` statements.
   */
  def path(m: Module)(using C: Context): String =
    (C.config.outputPath() / m.path.replace('/', '_')).unixPath + ".sml"


  def toML(p: Param): MLName = nameDef(p.id)

  def toML(e: Argument): ml.Expr = e match {
    case e: lifted.Expr => toML(e)
    case b: lifted.Block => toML(b)
    case e: lifted.Evidence => toML(e)
  }

  def toML(module: ModuleDecl): List[ml.Binding] = {
    toML(module.defs)
  }

  def toMLExpr(stmt: Stmt): ml.Expr = stmt match {
    case Return(e) => Call(Consts.pure)(toML(e))
    case App(b, targs, args) => Expr.Call(toML(b), args map toML)
    case If(cond, thn, els) => ml.If(toML(cond), toMLExpr(thn), toMLExpr(els))
    case Val(id, tpe, binding, body) => ??? // bind(toMLExpr(binding), nameDef(id), toML(body))
    case While(cond, body) => ??? // ml.Builtin("while", toMLExpr(cond), toMLExpr(body))
    case Match(scrutinee, clauses) => ???
    //      ml.Match(toML(scrutinee), clauses.map { case (pattern, branch) =>
    //        (toML(pattern), curry(toML(branch)))
    //      })

    case Hole => ???

    case State(id, init, region, body) if region == symbols.builtins.globalRegion => ???
    //      ml.Let(List(Binding(nameDef(id), ml.Builtin("box", toML(init)))), toML(body))

    case State(id, init, region, body) => ???
    //      ml.Let(List(Binding(nameDef(id), ml.Builtin("fresh", Variable(nameRef(region)), toML(init)))), toML(body))

    case Handle(body, tpe, handler) => ???
    //      val handlers: List[ml.Handler] = handler.map { h =>
    //        val names = RecordNames(h.id)
    //        ml.Handler(names.constructor, h.clauses.map {
    //          case (op, BlockLit(params, body)) =>
    //            // the LAST argument is the continuation...
    //            ml.Operation(nameDef(op), params.init.map(p => nameDef(p.id)), nameDef(params.last.id), toMLExpr(body))
    //        })
    //      }
    //      ml.Handle(handlers, toML(body))

    case Region(body) => ???
    //      ml.Builtin("with-region")(toML(body))

    case Let(Wildcard(_), tpe, binding, body) =>
      ml.Sequence(toML(binding), toMLExpr(body))

    case Let(id, tpe, binding, body) =>
      ml.Let(List(createBinder(id, binding)), toMLExpr(body))

    case other => println(other); ???
  }

  def createBinder(id: Symbol, binding: Expr): Binding = binding match {
    case Closure(b) => createBinder(id, b)
    case _ =>
      ml.ValBind(nameDef(id), toML(binding))
  }

  def createBinder(id: Symbol, binding: Block): Binding = {
    binding match {
      case BlockLit(params, body) =>
        ml.FunBind(nameDef(id), params map toML, toMLExpr(body))
      case Extern(params, body) =>
        ml.FunBind(nameDef(id), params map (p => MLName(p.id.name.name)), RawExpr(body))
      case _ =>
        ml.ValBind(nameDef(id), toML(binding))
    }
  }

  def toML(stmt: Stmt): List[ml.Binding] = stmt match {

    case Def(id, tpe, block, rest) =>
      val defs = toML(rest)
      val constDef = createBinder(id, block)
      constDef :: defs

    case Data(did, ctors, rest) => ???
    //      val defs = toML(rest)
    //      val constructors = ctors.flatMap(ctor => generateConstructor(ctor.asInstanceOf[effekt.symbols.Record]))
    //      Block(constructors ++ defs, exprs, result)

    case Record(did, fields, rest) => ???
    //      val Block(defs, exprs, result) = toML(rest)
    //      val constructors = generateConstructor(did, fields)
    //      Block(constructors ++ defs, exprs, result)

    case Include(contents, rest) =>
      val defs = toML(rest)
      val include = RawBind(contents)
      include :: defs

    case Let(Wildcard(_), tpe, binding, body) => ???
    //      toML(binding) match {
    //        // drop the binding altogether, if it is of the form:
    //        //   let _ = myVariable; BODY
    //        // since this might lead to invalid scheme code.
    //        case _: ml.Variable => toML(body)
    //        case _ => toML(body) match {
    //          case ml.Block(Nil, exprs, result) => ml.Block(Nil, toML(binding) :: exprs, result)
    //          case b => ml.Block(Nil, toML(binding) :: Nil, ml.Let(Nil, b))
    //        }
    //      }

    case Let(id, tpe, binding, body) =>
      val defs = toML(body)
      val constant = createBinder(id, binding)
      constant :: defs

    case other => Nil
  }

  def toML(block: BlockLit): ml.Lambda = block match {
    case BlockLit(params, body) =>
      ml.Lambda(params map toML: _*)(toMLExpr(body))
  }

  def toML(block: Block): ml.Expr = block match {
    case BlockVar(id) =>
      Variable(nameRef(id))

    case b@BlockLit(_, _) =>
      toML(b)

    case Member(b, field) => ???
    //      ml.Call(Variable(nameRef(field)))(List(toML(b)))

    case Extern(params, body) =>
      ml.Lambda(params map { p => MLName(p.id.name.name) }: _*)(ml.RawExpr(body))

    case Unbox(e) =>
      toML(e)

    case New(Handler(id, clauses)) => ???
    //      val MLName(name) = nameRef(id)
    //      ml.Call(Variable(MLName(s"make-${name}")), clauses.map { case (_, block) => toML(block) })
  }

  def toML(scope: Evidence): ml.Expr = scope match {
    case Evidence(Nil) => Variable(MLName("here"))
    case Evidence(ev :: Nil) => Variable(nameRef(ev))
    case Evidence(scopes) => println(scopes); ??? //ml.Builtin("nested", scopes map { s => ml.Variable(nameRef(s)) }:_*)
  }

  def toML(expr: Expr): ml.Expr = expr match {
    case UnitLit() => Consts.unitVal
    case StringLit(s) => MLString(s)
    case BooleanLit(b) => if (b) Consts.trueVal else Consts.falseVal
    case l: Literal[t] => ml.RawValue(l.value.toString)
    case ValueVar(id) => ml.Variable(nameRef(id))

    case PureApp(b, targs, args) => ml.Call(toML(b), args map {
      case e: Expr => toML(e)
      case b: Block => toML(b)
      case e: Evidence => toML(e)
    })

    case Select(b, field) =>
      ml.Call(nameRef(field))(toML(b))

    case Closure(b) => toML(b)

    case Run(s, tpe) => Call(Consts.run)(toMLExpr(s))
  }

  def toML(p: Pattern): ml.Expr = ???
  //  p match {
  //    case IgnorePattern()    => Variable(MLName("ignore"))
  //    case AnyPattern()       => Variable(MLName("any"))
  //    case LiteralPattern(l)  => Builtin("literal", toML(l))
  //    case TagPattern(id, ps) => Builtin("match-" + uniqueName(id), ps map { p => toML(p) }: _*)
  //  }
}
