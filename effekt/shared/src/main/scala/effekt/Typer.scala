package effekt
package typer

/**
 * In this file we fully qualify source types, but use symbols directly
 */
import effekt.context.{ Annotations, Context, ContextOps }
import effekt.context.assertions._
import effekt.source.{ AnyPattern, Def, MutualStmt, IgnorePattern, MatchPattern, ModuleDecl, Stmt, TagPattern, Term, Tree }
import effekt.substitutions._
import effekt.symbols._
import effekt.symbols.builtins._
import effekt.symbols.kinds._

/**
 * Typechecking
 * ============
 *
 * Preconditions:
 * --------------
 * Typer assumes that all dependencies already have been type checked.
 * In particular, it assumes that all definitions / symbols (functions, parameters etc.)
 * have been annotated with a type: this models a (global) typing context.
 *
 * Postconditions:
 * ---------------
 * All trees will be annotated with intermediate types (and effects). This is useful for
 * IDE support.
 * Also, after type checking, all definitions of the file will be annotated with their type.
 */
case class TyperResult[+T](tpe: T, capt: CaptureSet)
object / {
  def unapply[T](t: TyperResult[T]): Option[(T, CaptureSet)] = Some((t.tpe, t.capt))
}
object TyperResult {
  implicit class TypeOps[T](tpe: T) {
    def /(capt: CaptureSet): TyperResult[T] = TyperResult(tpe, capt)
  }
}
import TyperResult._

class Typer extends Phase[ModuleDecl, ModuleDecl] {

  val phaseName = "typer"

  def run(module: ModuleDecl)(implicit C: Context): Option[ModuleDecl] = try {
    val mod = Context.module

    Context.initTyperstate()

    Context in {
      Context.withUnificationScope {
        // We split the type-checking of definitions into "pre-check" and "check"
        // to allow mutually recursive defs
        checkStmt(source.MutualStmt(module.defs, source.Return(source.UnitLit())))
      }
    }

    if (C.buffer.hasErrors) {
      None
    } else {
      Some(module)
    }
  } finally {
    // Store the backtrackable annotations into the global DB
    // This is done regardless of errors, since
    Context.commitTypeAnnotations()

  }

  // checks an expression in second-class position
  //<editor-fold desc="blocks">

  def insertBoxing(expr: Term)(implicit C: Context): TyperResult[ValueType] = expr match {
    case source.Var(id) => checkExpr(source.Box(None, source.InterfaceArg(id).inheritPosition(id)).inheritPosition(expr))
    case _              => Context.abort("Currently automatic boxing is only supported for variables, please bind the block first.")
  }

  def insertUnboxing(expr: Term)(implicit C: Context): TyperResult[BlockType] =
    checkExprAsBlock(source.Unbox(expr).inheritPosition(expr))

  /**
   * We defer checking whether something is first-class or second-class to Typer now.
   */
  def checkExprAsBlock(expr: Term)(implicit C: Context): TyperResult[BlockType] =
    checkBlock(expr) {
      case source.Unbox(expr) =>
        val vtpe / capt1 = checkExpr(expr)
        // TODO here we also need unification variables for block types!
        // C.unify(tpe, BoxedType())
        vtpe match {
          case BoxedType(btpe, capt2) => btpe / (capt1 ++ capt2)
          case _ => Context.abort(s"Unbox requires a boxed type, but got $vtpe")
        }

      case source.Var(id) => id.symbol match {
        case b: BlockSymbol =>
          val (tpe, capt) = Context.lookup(b)
          tpe / capt
        case e: ValueSymbol => insertUnboxing(expr)
      }

      case s @ source.Select(expr, selector) =>
        checkExprAsBlock(expr) match {
          case ((i @ InterfaceType(interface, targs)) / capt) =>
            // (1) find the operation
            // try to find an operation with name "selector"
            val op = interface.ops.collect {
              case op if op.name.name == selector.name => op
            } match {
              case Nil      => Context.at(s) { Context.abort(s"Cannot select ${selector.name} in type ${i}") }
              case List(op) => op
              case _        => Context.at(s) { Context.abort(s"Multiple operations match ${selector.name} in type ${i}") }
            }
            // assign the resolved operation to the identifier
            Context.assignSymbol(selector, op)

            // (2) substitute type arguments
            val tsubst = (interface.tparams zip targs).toMap
            tsubst.substitute(op.toType) / capt

          case _ => Context.abort(s"Selection requires an interface type.")
        }

      case _ => Context.abort(s"Expected something of a block type.")
    }

  //</editor-fold>

  //<editor-fold desc="expressions">

  def checkExpr(expr: Term)(implicit C: Context): TyperResult[ValueType] =
    check(expr) {
      case source.IntLit(n)     => TInt / Pure
      case source.BooleanLit(n) => TBoolean / Pure
      case source.UnitLit()     => TUnit / Pure
      case source.DoubleLit(n)  => TDouble / Pure
      case source.StringLit(s)  => TString / Pure

      case source.If(cond, thn, els) =>
        val cndTpe / cndCapt = cond checkAgainst TBoolean
        val thnTpe / thnCapt = checkStmt(thn)
        val elsTpe / elsCapt = checkStmt(els)

        Context.unify(thnTpe, elsTpe)

        thnTpe / (cndCapt ++ thnCapt ++ elsCapt)

      case source.While(cond, block) =>
        val _ / cndCapt = cond checkAgainst TBoolean
        val _ / blkCapt = block checkAgainst TUnit
        TUnit / (cndCapt ++ blkCapt)

      // the variable now can also be a block variable
      case source.Var(id) => id.symbol match {
        case b: VarBinder => Context.lookup(b) match {
          case (BlockTypeApp(TState, List(tpe)), capt) => tpe / capt
          case _ => Context.panic(s"Builtin state cannot be typed.")
        }
        case b: BlockSymbol => insertBoxing(expr)
        case x: ValueSymbol => Context.lookup(x) / Pure
      }

      case e @ source.Assign(id, expr) =>
        // assert that it is a mutable variable
        val sym = e.definition.asVarBinder
        val stTpe / stCapt = Context.lookup(sym) match {
          case (BlockTypeApp(TState, List(tpe)), capt) => tpe / capt
          case _ => Context.panic(s"Builtin state cannot be typed.")
        }
        val _ / exprCapt = expr checkAgainst stTpe

        TUnit / (stCapt ++ exprCapt)

      case source.Box(annotatedCapt, block) =>
        // by introducing a unification scope here, we know that `capt` cannot contain fresh unification variables.
        val inferredTpe / inferredCapt = Context.at(block) { Context withUnificationScope { checkBlockArgument(block) } }

        // box { ... }  ~>  box ?C { ... }
        val capt = annotatedCapt.map { c =>
          val expected = c.resolve
          C.sub(inferredCapt, expected)
          expected
        }.getOrElse(inferredCapt)

        // If there is no annotated capture set, we simply use the inferred one. This might lead to type errors
        // up higher.
        BoxedType(inferredTpe, capt) / Pure

      case source.Unbox(_) => insertBoxing(expr)

      case c @ source.Call(e, targsTree, vargs, bargs) =>
        val funTpe / funCapt = checkExprAsBlock(e) match {
          case TyperResult(b: FunctionType, capt) => b / capt
          case _ => Context.abort("Callee is required to have function type")
        }

        val targs = targsTree map { _.resolve }

        // (1) Instantiate blocktype
        // e.g. `[A, B] (A, A) => B` becomes `(?A, ?A) => ?B`
        // TODO: do something about capture params
        val (trigids, crigids, bt @ FunctionType(_, _, vparams, bparams, ret)) = Context.instantiate(funTpe)

        // (2) Wellformedness -- check arity
        if (targs.nonEmpty && targs.size != trigids.size)
          Context.abort(s"Wrong number of type arguments ${targs.size}")

        if (vparams.size != vargs.size)
          Context.error(s"Wrong number of value arguments, given ${vargs.size}, but function expects ${vparams.size}.")

        if (bparams.size != bargs.size)
          Context.error(s"Wrong number of block arguments, given ${bargs.size}, but function expects ${bparams.size}.")

        // (3) Unify with provided type arguments, if any.
        if (targs.nonEmpty) {
          (trigids zip targs) map { case (r, a) => Context.unify(r, a) }
        }

        var argCapt = Pure
        (vparams zip vargs) foreach {
          case (paramType, arg) =>
            val _ / capt = arg checkAgainst paramType
            argCapt ++= capt
        }
        (bparams zip bargs zip crigids) foreach {
          case ((paramType, arg), cvar) =>
            val got / capt = checkBlockArgument(arg)
            C.unify(paramType, got)

            // here we use unify, not sub since this really models substitution
            C.unify(capt, cvar)
            argCapt ++= capt
        }

        ret / (funCapt ++ argCapt)

      // TODO implement properly
      case d @ source.Region(id, body) =>
        val param = d.symbol
        val capt = CaptureSet(CaptureOf(param))

        Context.bind(param)
        Context.withRegion(capt) {
          // unification scope is important here to be able to check escaping
          val tpe / bodyCapt = Context.withUnificationScope { checkStmt(body) }
          checkNonEscape(capt, d, tpe)
          tpe / (bodyCapt -- capt)
        }

      case source.TryHandle(prog, handlers) =>

        val capabilityParams = handlers.map { h => h.capability.symbol }

        // the current region is delimited by this handler
        val lexical = CaptureSet(capabilityParams map CaptureOf)

        // (1) create new unification scope and check handled program (`prog`) with capabilities in scope
        val ret / bodyCapt = Context.at(prog) {
          Context.withUnificationScope {
            // bind capability types in type environment
            capabilityParams foreach Context.bind
            Context.withRegion(lexical) { checkStmt(prog) }
          }
        }

        val boundCaptures: Set[Capture] = capabilityParams.map(CaptureOf).toSet

        // subtract capability from inferred capture Cp. Outer unification variables cannot possibly contain the fresh capability.
        val bodyCaptWithoutCapabilities = bodyCapt -- CaptureSet(boundCaptures)

        // check that none of the bound capabilities escapes through the return type
        checkNonEscape(CaptureSet(boundCaptures), prog, ret)

        // Create a new unification scope and introduce a fresh capture variable for the continuations ?Ck
        Context.withUnificationScope {

          var handlerCapt = Pure

          // the capture variable for the continuation ?Ck
          val resumeCapture = C.freshCaptVar(CaptureParam(LocalName("$resume")))

          // Check all handler bodies and collect all inferred capture sets Ch
          handlers foreach Context.withFocus { h =>
            // try { ... } with s: >>>State[Int]<<< { ... }
            val annotatedType @ InterfaceType(interface, targs) = h.capability.symbol.tpe.asInterfaceType

            val tparams = interface.tparams
            val tsubst = (tparams zip targs).toMap

            // (3) check all operations are covered
            val covered = h.clauses.map { _.definition }
            val notCovered = interface.ops.toSet -- covered.toSet

            if (notCovered.nonEmpty) {
              val explanation = notCovered.map { op => s"${op.name} of interface ${op.interface.name}" }.mkString(", ")
              Context.error(s"Missing definitions for operations: ${explanation}")
            }

            if (covered.size > covered.distinct.size)
              Context.error(s"Duplicate definitions of operations")

            // (4) actually check each clause
            h.clauses foreach Context.withFocus {
              // TODO what is with type parameters of operation clauses?
              case d @ source.OpClause(op, tparams, vparams, body, resume) =>

                val declaration = d.definition

                // (4a) the effect operation might refer to type parameters of the interface
                //   i.e. interface Foo[A] { def bar[B](a: A): B }
                //
                // at the handle site, we might have
                //   try { ... } with f: Foo[Int] { def bar[C](a: Int): C }
                //
                // So as a first step, we need to obtain the function type of the declaration bar:
                //   bar: [B](a: A) -> B
                // and substitute { A -> Int}
                //   barSubstituted: [B](a: Int) -> B
                // TODO: do something about capture set
                val FunctionType(tparams1, cparams1, vparams1, bparams1, ret1) = tsubst.substitute(declaration.toType)

                // (4b) check the body of the clause
                val tparamSyms = tparams.map { t => t.symbol.asTypeVar }
                val vparamSyms = vparams.map { p => p.symbol }

                vparamSyms foreach Context.bind

                // TODO add constraints on resume capture
                val resumeType = FunctionType(Nil, Nil, List(ret1), Nil, ret)
                val resumeSym = Context.symbolOf(resume).asBlockSymbol

                Context.bind(resumeSym, resumeType, CaptureSet(resumeCapture))

                // TODO does this need to be a fresh unification scope?
                val opRet / opCapt = Context in { body checkAgainst ret }
                handlerCapt ++= opCapt

                // (4c) Note that the expected type is NOT the declared type but has to take the answer type into account
                /** TODO: do something about capture set parameters */
                val inferredTpe = FunctionType(tparamSyms, Nil, vparamSyms.map { Context.lookup }, Nil, opRet)
                val expectedTpe = FunctionType(tparams1, cparams1, vparams1, bparams1, ret)

                Context.unify(inferredTpe, expectedTpe)
            }
          }

          // Ck = (Cp - cap) union (UNION Ch)
          val residualCapt = bodyCaptWithoutCapabilities ++ handlerCapt
          C.unify(CaptureSet(resumeCapture), residualCapt)

          ret / residualCapt
        }

      case source.Match(sc, clauses) =>

        // (1) Check scrutinee
        // for example. tpe = List[Int]
        val scTpe / scCapt = Context.withUnificationScope { checkExpr(sc) }

        var capt = scCapt

        // (2) check exhaustivity
        checkExhaustivity(scTpe, clauses.map { _.pattern })

        // (3) infer types for all clauses
        // TODO here we would need multi arity constraints!
        val (firstTpe / firstCapt, firstTree) :: clauseTpes = clauses.map {
          case c @ source.MatchClause(p, body) =>
            val res = Context.withUnificationScope {
              Context in {
                Context.bind(checkPattern(scTpe, p))
                checkStmt(body)
              }
            }
            (res, body)
        }

        capt ++= firstCapt

        // (4) unify clauses and collect effects
        clauseTpes foreach {
          case (clauseTpe / clauseCapt, tree) =>
            capt ++= clauseCapt
            Context.at(tree) { Context.unify(firstTpe, clauseTpe) }
        }

        firstTpe / capt

      case source.Select(_, _) =>
        insertBoxing(expr)

      case source.Hole(stmt) =>
        checkStmt(stmt)
        THole / Pure
    }

  def checkNonEscape(bound: CaptureSet, prog: Tree, ret: Type)(implicit C: Context): Unit = {
    val escape = freeCapture(ret) intersect bound.captures
    if (escape.nonEmpty) {
      C.at(prog) { C.error(s"The type ${ret} is not allowed to refer to any of the bound capabilities, but mentions: ${CaptureSet(escape)}") }
    }
  }

  //</editor-fold>

  //<editor-fold desc="pattern matching">

  /**
   * This is a quick and dirty implementation of coverage checking. Both performance, and error reporting
   * can be improved a lot.
   */
  def checkExhaustivity(sc: ValueType, cls: List[MatchPattern])(implicit C: Context): Unit = {
    val catchall = cls.exists { p => p.isInstanceOf[AnyPattern] || p.isInstanceOf[IgnorePattern] }

    if (catchall)
      return ;

    sc match {
      case TypeConstructor(t: DataType) =>
        t.variants.foreach { variant =>
          checkExhaustivity(variant, cls)
        }

      case TypeConstructor(t: Record) =>
        val (related, unrelated) = cls.collect { case p: TagPattern => p }.partitionMap {
          case p if p.definition == t => Left(p.patterns)
          case p => Right(p)
        }

        if (related.isEmpty) {
          Context.error(s"Non exhaustive pattern matching, missing case for ${sc}")
        }

        (t.fields.map { f => f.tpe } zip related.transpose) foreach {
          case (t, ps) => checkExhaustivity(t, ps)
        }
      case other =>
        ()
    }
  }

  def checkPattern(sc: ValueType, pattern: MatchPattern)(implicit C: Context): Map[Symbol, ValueType] = Context.focusing(pattern) {
    case source.IgnorePattern()    => Map.empty
    case p @ source.AnyPattern(id) => Map(p.symbol -> sc)
    case p @ source.LiteralPattern(lit) =>
      lit.checkAgainst(sc)
      Map.empty
    case p @ source.TagPattern(id, patterns) =>

      // symbol of the constructor we match against
      val sym: Record = Context.symbolOf(id) match {
        case c: Record => c
        case _         => Context.abort("Can only match on constructors")
      }

      // (4) Compute blocktype of this constructor with rigid type vars
      // i.e. Cons : `(?t1, List[?t1]) => List[?t1]`
      // constructors can't take block parameters, so we can ignore them safely
      val (trigids, crigids, FunctionType(_, _, vpms, _, ret)) = Context.instantiate(sym.toType)

      // (5) given a scrutinee of `List[Int]`, we learn `?t1 -> Int`
      Context.unify(ret, sc)
      //
      //      // (6) check for existential type variables
      //      // at the moment we do not allow existential type parameters on constructors.
      //      val skolems = Context.skolems(rigids)
      //      if (skolems.nonEmpty) {
      //        Context.error(s"Unbound type variables in constructor ${id}: ${skolems.map(_.underlying).mkString(", ")}")
      //      }

      //        // (7) refine parameter types of constructor
      //        // i.e. `(Int, List[Int])`
      //        val constructorParams = vpms map { p => Context.unifier substitute p }

      // (8) check nested patterns
      var bindings = Map.empty[Symbol, ValueType]

      (patterns, vpms) match {
        case (pats, pars) =>
          if (pats.size != pars.size)
            Context.error(s"Wrong number of pattern arguments, given ${pats.size}, expected ${pars.size}.")

          (pats zip pars) foreach {
            case (pat, par: ValueType) =>
              bindings ++= checkPattern(par, pat)
            case _ =>
              Context.panic("Should not happen, since constructors can only take value parameters")
          }
      }
      bindings
  }

  //</editor-fold>

  //<editor-fold desc="statements and definitions">

  def checkStmt(stmt: Stmt)(implicit C: Context): TyperResult[ValueType] = check(stmt) {
    case source.MutualStmt(defs, rest) => Context.withUnificationScope {
      defs foreach precheckDef

      var capt = Pure
      defs foreach { d => capt ++= checkDef(d) }

      val restTpe / restCapt = checkStmt(rest)
      restTpe / (restCapt ++ capt)
    }

    case d @ source.ValDef(id, annot, binding, rest) =>
      val tpeBind / captBind = d.symbol.tpe match {
        case Some(t) =>
          binding checkAgainst t
        case None => checkStmt(binding)
      }
      Context.bind(d.symbol, tpeBind)

      val tpeRest / captRest = checkStmt(rest)

      tpeRest / (captBind ++ captRest)

    case d @ source.VarDef(id, annot, reg, binding, rest) =>
      val sym = d.symbol

      // we use the current region as an approximation for the state
      val captState = reg map Context.symbolOf map {
        case b: BlockSymbol =>
          Context.lookup(b) match {
            case (TRegion, capt) => capt
            case _               => Context.at(reg.get) { Context.abort(s"Expected a region.") }
          }
        case _ => Context.at(reg.get) { Context.abort(s"Expected a region.") }
      } getOrElse C.region

      val tpeBind / captBind = sym.tpe match {
        case Some(t) => binding checkAgainst t
        case None    => checkStmt(binding)
      }
      val stTpe = BlockTypeApp(TState, List(tpeBind))

      Context.bind(sym, stTpe, captState)

      val tpeRest / captRest = checkStmt(rest)

      tpeRest / (captState ++ captBind ++ captRest)

    case source.ExprStmt(e, rest) =>
      val _ / captExpr = checkExpr(e)
      val tpe / captRest = checkStmt(rest)
      tpe / (captExpr ++ captRest)

    case source.Return(e)        => checkExpr(e)

    case source.BlockStmt(stmts) => checkStmt(stmts)
  }

  // not really checking, only if defs are fully annotated, we add them to the typeDB
  // this is necessary for mutually recursive definitions
  //
  // we also need to create fresh capture variables to collect constraints
  def precheckDef(d: Def)(implicit C: Context): Unit = Context.focusing(d) {
    case d @ source.FunDef(id, tparams, vparams, bparams, ret, body) =>
      val funSym = d.symbol
      val funCapt = CaptureSet(C.freshCaptVar(CaptureOf(funSym)))
      Context.bind(funSym, funCapt)
      funSym.ret.foreach { annot => Context.bind(d.symbol, d.symbol.toType) }

    case d @ source.BlockDef(id, tpe, body) =>
      val blockSym = d.symbol
      val blockCapt = CaptureSet(C.freshCaptVar(CaptureOf(blockSym)))
      Context.bind(blockSym, blockCapt)
      blockSym.tpe.foreach { annot => Context.bind(d.symbol, annot) }

    case d @ source.ExternFun(pure, id, tparams, params, tpe, body) =>
      Context.bind(d.symbol, d.symbol.toType, Pure)

    case d @ source.InterfaceDef(id, tparams, ops) =>
      d.symbol.ops.foreach { op =>
        val tpe = op.toType
        wellformed(tpe)
        Context.bind(op, tpe, Pure)
      }

    case source.DataDef(id, tparams, ctors) =>
      ctors.foreach { ctor =>
        val sym = ctor.symbol
        Context.bind(sym, sym.toType, Pure)

        sym.fields.foreach { field =>
          val tpe = field.toType
          wellformed(tpe)
          Context.bind(field, tpe, Pure)
        }
      }

    case d: source.ExternInclude => ()
    case d: source.ExternType    => ()
  }

  def checkDef(d: Def)(implicit C: Context): CaptureSet =
    Context.focusing(d) {
      case d @ source.BlockDef(id, tpe, body) =>
        val sym = d.symbol
        val precheckedCapt = C.lookupCapture(sym)
        val lexical = CaptureOf(sym)
        val tpe / capt = Context.at(body) {
          Context.withRegion(CaptureSet(lexical)) {
            Context.withUnificationScope {
              sym.tpe match {
                case Some(tpe) => body checkAgainst tpe
                case None      => checkBlockArgument(body)
              }
            }
          }
        }

        checkNonEscape(CaptureSet(lexical), d, tpe)
        Context.bind(sym, tpe, capt)
        Context.annotateInferredType(d, tpe)
        Context.annotateInferredCapt(d, capt)

        // since we do not have capture annotations for now, we do not need subsumption here and this is really equality
        C.unify(capt, precheckedCapt)
        // this is important for use vs. mention -- we annotate the block with the capture set and only require it, when it is USED
        Pure

      case d @ source.FunDef(id, tparams, vparams, bparams, ret, body) =>
        val sym = d.symbol
        sym.vparams foreach Context.bind
        sym.bparams foreach Context.bind

        val precheckedCapt = C.lookupCapture(sym)

        val lexical = CaptureOf(sym)

        val tpe / capt = Context.at(body) {
          Context.withRegion(CaptureSet(lexical)) {
            Context.withUnificationScope {
              sym.ret match {
                case Some(tpe) => body checkAgainst tpe
                case None      => checkStmt(body)
              }
            }
          }
        }

        checkNonEscape(CaptureSet(lexical), d, tpe)

        // TODO check whether the subtraction here works in presence of unification variables
        val captWithoutBoundParams = capt -- CaptureSet(sym.bparams.map(CaptureOf)) -- CaptureSet(lexical)

        Context.bind(sym, sym.toType(tpe), captWithoutBoundParams)
        Context.annotateInferredType(d, tpe)
        Context.annotateInferredCapt(d, captWithoutBoundParams)

        // since we do not have capture annotations for now, we do not need subsumption here and this is really equality
        C.unify(captWithoutBoundParams, precheckedCapt)
        // this is important for use vs. mention -- we annotate the block with the capture set and only require it, when it is USED
        Pure

      case d @ source.ExternFun(pure, id, tparams, vparams, tpe, body) =>
        d.symbol.vparams map { p => Context.bind(p) }
        Pure

      // All other defs have already been prechecked
      case d: source.InterfaceDef  => Pure
      case d: source.DataDef       => Pure
      case d: source.ExternInclude => Pure
      case d: source.ExternType    => Pure
    }

  //</editor-fold>

  //<editor-fold desc="arguments and parameters">

  def checkBlockArgument(arg: source.BlockArg)(implicit C: Context): TyperResult[BlockType] = checkBlock(arg) {
    case arg: source.FunctionArg  => checkFunctionArgument(arg)
    case arg: source.InterfaceArg => checkExprAsBlock(source.Var(arg.id).inheritPosition(arg))
    case source.UnboxArg(expr) =>
      val tpe / outerCapt = checkExpr(expr)
      // TODO this is a conservative approximation:
      //    the capture of the expr should be part of the capture of the call, but not the block argument!
      tpe match {
        case BoxedType(btpe, capt) => btpe / (capt ++ outerCapt)
        case _                     => C.abort(s"Unboxing requires a boxed type, but got $tpe")
      }

    // TODO share with effect handlers, this is mostly copy and pasted
    case n @ source.NewArg(tpe, members) =>

      // The self region is created by Namer and stored in the DB
      val lexical = C.annotation(Annotations.InferredCapture, n)

      var capt = Pure

      // new >>>State[Int]<<< { ... }
      val annotatedType @ InterfaceType(interface, targs) = tpe.resolve.asInterfaceType

      val tparams = interface.tparams
      val tsubst = (tparams zip targs).toMap

      // (3) check all operations are covered
      val covered = members.map { _.definition }
      val notCovered = interface.ops.toSet -- covered.toSet

      if (notCovered.nonEmpty) {
        val explanation = notCovered.map { op => s"${op.name} of interface ${op.interface.name}" }.mkString(", ")
        Context.error(s"Missing definitions for operations: ${explanation}")
      }

      if (covered.size > covered.distinct.size)
        Context.error(s"Duplicate definitions of operations")

      // (4) actually check each clause
      members foreach Context.withFocus {
        // TODO what is with type parameters of operation clauses?
        case d @ source.OpClause(op, tparams, vparams, body, _) =>

          val declaration = d.definition

          val FunctionType(tparams1, cparams1, vparams1, bparams1, ret1) = tsubst.substitute(declaration.toType)

          // (4b) check the body of the clause
          val tparamSyms = tparams.map { t => t.symbol.asTypeVar }
          val vparamSyms = vparams.map { p => p.symbol }

          vparamSyms foreach Context.bind

          // TODO does this need to be a fresh unification scope?
          val opRet / opCapt = Context in { body checkAgainst ret1 }
          capt ++= opCapt

          // (4c) Note that the expected type is NOT the declared type but has to take the answer type into account
          /** TODO: do something about capture set parameters */
          val inferredTpe = FunctionType(tparamSyms, Nil, vparamSyms.map { Context.lookup }, Nil, opRet)
          val expectedTpe = FunctionType(tparams1, cparams1, vparams1, bparams1, ret1)

          Context.unify(inferredTpe, expectedTpe)
      }

      annotatedType / capt
  }

  // Example.
  //   BlockParam: def foo { f: Int => String / Print }
  //   BlockArg: foo { n => println("hello" + n) }
  //     or
  //   BlockArg: foo { (n: Int) => println("hello" + n) }
  def checkFunctionArgument(arg: source.FunctionArg)(implicit C: Context): TyperResult[FunctionType] = arg match {
    case decl @ source.FunctionArg(tparams, vparams, bparams, body) =>
      val tparamSymbols = tparams.map { p => p.symbol.asTypeVar }
      tparamSymbols.foreach { p => Context.bind(p, p) }
      vparams.foreach { p => Context.bind(p.symbol) }
      bparams.foreach { p => Context.bind(p.symbol) }
      val capts = bparams.map { p => CaptureOf(p.symbol) }

      // The self region is created by Namer and stored in the DB
      val lexical = C.annotation(Annotations.InferredCapture, decl)

      // TODO should we open a new unification scope here?
      val ret / capt = Context.withRegion(lexical) { checkStmt(body) }

      checkNonEscape(lexical, arg, ret)

      val capture = capt -- CaptureSet(capts) -- lexical

      FunctionType(tparamSymbols, capts, vparams.map { p => p.symbol.tpe }, bparams.map { p => p.symbol.tpe }, ret) / capture
  }

  //</editor-fold>

  private implicit class ExprOps(expr: Term) {
    def checkAgainst(tpe: ValueType)(implicit C: Context): TyperResult[ValueType] = Context.at(expr) {
      val got / capt = checkExpr(expr)
      C.unify(tpe, got)
      tpe / capt
    }
    def checkAgainst(tpe: BlockType)(implicit C: Context): TyperResult[BlockType] = Context.at(expr) {
      val got / capt = checkExprAsBlock(expr)
      C.unify(tpe, got)
      tpe / capt
    }
  }

  private implicit class BlockArgOps(expr: source.BlockArg) {
    def checkAgainst(tpe: BlockType)(implicit C: Context): TyperResult[BlockType] = Context.at(expr) {
      val got / capt = checkBlockArgument(expr)
      C.unify(tpe, got)
      tpe / capt
    }
  }

  private implicit class StmtOps(stmt: Stmt) {
    def checkAgainst(tpe: ValueType)(implicit C: Context): TyperResult[ValueType] = Context.at(stmt) {
      val got / capt = checkStmt(stmt)
      C.unify(tpe, got)
      tpe / capt
    }
  }

  /**
   * Combinators that also store the computed type for a tree in the TypesDB
   */
  def check[T <: Tree](t: T)(f: T => TyperResult[ValueType])(implicit C: Context): TyperResult[ValueType] =
    Context.at(t) {
      val got / capt = f(t)
      wellformed(got)
      C.annotateInferredType(t, got)
      C.annotateInferredCapt(t, capt)
      got / capt
    }

  def checkBlock[T <: Tree](t: T)(f: T => TyperResult[BlockType])(implicit C: Context): TyperResult[BlockType] =
    Context.at(t) {
      val got / capt = f(t)
      wellformed(got)
      C.annotateInferredType(t, got)
      C.annotateInferredCapt(t, capt)
      got / capt
    }
}

trait TyperOps extends ContextOps { self: Context =>

  /**
   * The current unification Scope
   */
  private var scope: UnificationScope = new UnificationScope

  /**
   * The substitutions learnt so far
   */
  private var substitutions: Substitutions = Substitutions.empty

  /**
   * We need to substitute after solving and update the DB again, later.
   */
  private var inferredValueTypes: List[(Tree, ValueType)] = Nil
  private var inferredBlockTypes: List[(Tree, BlockType)] = Nil
  private var inferredCaptures: List[(Tree, CaptureSet)] = Nil

  /**
   * The current lexical region used for mutable variables.
   *
   * None on the toplevel
   */
  private var lexicalRegion: Option[CaptureSet] = None

  private[typer] def initTyperstate(): Unit = {
    // Clear annotations, we are about to recompute those
    annotate(Annotations.CaptureForFile, module, Nil)

    scope = new UnificationScope
    inferredValueTypes = List.empty
    inferredBlockTypes = List.empty
    inferredCaptures = List.empty
    valueTypingContext = Map.empty
    blockTypingContext = Map.empty
    substitutions = Substitutions.empty
  }

  private[typer] def commitTypeAnnotations(): Unit = {
    // now also store the typing context in the global database:
    valueTypingContext foreach { case (s, tpe) => assignType(s, tpe) }
    blockTypingContext foreach { case (s, tpe) => assignType(s, tpe) }
    captureContext foreach { case (s, c) => assignCaptureSet(s, c) }

    // Update and write out all inferred types and captures for LSP support
    // This info is currently also used by Transformer!
    inferredValueTypes foreach { case (t, tpe) => annotate(Annotations.InferredValueType, t, subst.substitute(tpe)) }
    inferredBlockTypes foreach { case (t, tpe) => annotate(Annotations.InferredBlockType, t, subst.substitute(tpe)) }

    val substitutedCaptures = inferredCaptures map { case (t, capt) => (t, subst.substitute(capt)) }
    // TODO maybe this is not necessary anymore
    substitutedCaptures foreach { case (t, capt) => annotate(Annotations.InferredCapture, t, capt) }

    annotate(Annotations.CaptureForFile, module, substitutedCaptures)
  }

  // Unification
  // ===========

  // opens a fresh unification scope
  private[typer] def withUnificationScope[T <: Type](block: => TyperResult[T]): TyperResult[T] = {
    val outerScope = scope
    scope = new UnificationScope
    val tpe / capt = block
    // leaving scope: solve here and check all are local unification variables are defined...
    val (subst, cs, ccs) = scope.solve

    // TODO applying the substitution to the environment is not enough! We also need to substitute into types and captures
    // that are locally in scope in Typer...

    // The unification variables now go out of scope:
    // use the new substitution to update the defined symbols (typing context) and inferred types (annotated trees).
    valueTypingContext = valueTypingContext.view.mapValues { t => subst.substitute(t) }.toMap
    blockTypingContext = blockTypingContext.view.mapValues { t => subst.substitute(t) }.toMap
    captureContext = captureContext.view.mapValues { c => subst.substitute(c) }.toMap
    substitutions = substitutions.updateWith(subst)

    outerScope.addAllType(cs)
    outerScope.addAllCapt(ccs)
    scope = outerScope

    tpe match {
      case t: ValueType => subst.substitute(t).asInstanceOf[T] / subst.substitute(capt)
      case t: BlockType => subst.substitute(t).asInstanceOf[T] / subst.substitute(capt)
    }
  }

  def unify(t1: ValueType, t2: ValueType) = scope.requireEqual(t1, t2)
  def unify(t1: BlockType, t2: BlockType) = scope.requireEqual(t1, t2)
  def unify(c1: CaptureSet, c2: CaptureSet) = scope.requireEqual(c1, c2)

  def sub(c1: CaptureSet, c2: CaptureSet) = scope.requireSub(c1, c2)

  def subst: Substitutions = substitutions

  // these are different methods because those are constraints that we like to introduce
  // might help refactoring later
  def unify(c1: CaptureSet, c2: Capture): Unit = unify(c1, CaptureSet(Set(c2)))
  def sub(c1: CaptureSet, c2: Capture): Unit = sub(c1, CaptureSet(Set(c2)))

  def instantiate(tpe: FunctionType) = scope.instantiate(tpe)

  // TODO this is only for synthesized capture sets (i.e. when box isn't annotated)
  def freshCaptVar() = scope.freshCaptVar(CaptureParam(NoName))
  def freshCaptVar(underlying: Capture) = scope.freshCaptVar(underlying)

  // Inferred types
  // ==============

  private[typer] def annotateInferredType(t: Tree, e: ValueType) = inferredValueTypes = (t -> e) :: inferredValueTypes
  private[typer] def annotateInferredType(t: Tree, e: BlockType) = inferredBlockTypes = (t -> e) :: inferredBlockTypes
  private[typer] def annotateInferredCapt(t: Tree, e: CaptureSet) = inferredCaptures = (t -> e) :: inferredCaptures

  // The "Typing Context"
  // ====================
  // since symbols are unique, we can use mutable state instead of reader
  private var valueTypingContext: Map[Symbol, ValueType] = Map.empty
  private var blockTypingContext: Map[Symbol, BlockType] = Map.empty
  private var captureContext: Map[Symbol, CaptureSet] = Map.empty

  // first tries to find the type in the local typing context
  // if not found, it tries the global DB, since it might be a symbol of an already checked dependency
  private[typer] def lookup(s: ValueSymbol) =
    valueTypingContext.getOrElse(s, valueTypeOf(s))

  private[typer] def lookup(s: BlockSymbol) = (lookupType(s), lookupCapture(s))

  private[typer] def lookupType(s: BlockSymbol) =
    blockTypingContext.get(s).orElse(blockTypeOption(s)).getOrElse(abort(s"Cannot find type for ${s.name.name} -- (mutually) recursive functions need to have an annotated return type."))

  private[typer] def lookupCapture(s: BlockSymbol) =
    captureContext.getOrElse(s, captureOf(s))

  private[typer] def bind(s: Symbol, tpe: ValueType): Unit = valueTypingContext += (s -> tpe)

  private[typer] def bind(s: Symbol, tpe: BlockType, capt: CaptureSet): Unit = { bind(s, tpe); bind(s, capt) }

  private[typer] def bind(s: Symbol, tpe: BlockType): Unit = blockTypingContext += (s -> tpe)

  private[typer] def bind(s: Symbol, capt: CaptureSet): Unit = captureContext += (s -> capt)

  private[typer] def bind(bs: Map[Symbol, ValueType]): Unit =
    bs foreach {
      case (v: ValueSymbol, t: ValueType) => bind(v, t)
      //        case (v: BlockSymbol, t: FunctionType) => bind(v, t)
      case other => panic(s"Internal Error: wrong combination of symbols and types: ${other}")
    }

  private[typer] def bind(p: ValueParam): Unit = p match {
    case s @ ValueParam(name, tpe) => bind(s, tpe)
    case s => panic(s"Internal Error: Cannot add $s to typing context.")
  }

  private[typer] def bind(p: BlockParam): Unit = p match {
    case s @ BlockParam(name, tpe) => bind(s, tpe, CaptureSet(CaptureOf(s)))
    case s => panic(s"Internal Error: Cannot add $s to typing context.")
  }

  // Lexical Regions
  // ===============
  def region: CaptureSet = lexicalRegion.getOrElse(abort("Mutable variables are not allowed outside of a function definition"))
  def withRegion[T](c: CaptureSet)(prog: => T): T = {
    val before = lexicalRegion
    lexicalRegion = Some(c)
    val res = prog
    lexicalRegion = before
    res
  }
}