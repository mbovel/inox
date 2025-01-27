/* Copyright 2009-2018 EPFL, Lausanne */

package inox
package tip

import utils._

import smtlib.lexer.{Tokens => LT, _}
import smtlib.trees.Commands.{FunDef => SMTFunDef, _}
import smtlib.trees.Terms.{Let => SMTLet, Forall => SMTForall, Identifier => SMTIdentifier, _}
import smtlib.theories._
import smtlib.theories.experimental._
import smtlib.extensions.tip.Terms.{Lambda => SMTLambda, Application => SMTApplication, _}
import smtlib.extensions.tip.Commands._

import Terms.{Assume => SMTAssume, Choose => SMTChoose}
import Commands._

import scala.collection.BitSet
import java.io.{Reader, File, BufferedReader, FileReader}

import scala.language.implicitConversions

class MissformedTIPException(reason: String, pos: Position)
  extends Exception("Missformed TIP source @" + pos + ":\n" + reason)

class Parser(reader: Reader, file: Option[File]) {
  import inox.trees.{given, _}

  protected val positions = new PositionProvider(new BufferedReader(reader), file)

  protected implicit def smtlibPositionToPosition(pos: Option[_root_.smtlib.common.Position]): Position = {
    pos.map(p => positions.get(p.line, p.col)).getOrElse(NoPosition)
  }

  def parseScript: Seq[(InoxProgram, Expr)] = {
    val parser = new TipParser(new TipLexer(positions.reader))
    val script = parser.parseScript

    var assertions: Seq[Expr] = Seq.empty
    var locals: Locals = NoLocals

    (for (cmd <- script.commands) yield cmd match {
      case CheckSat() =>
        val expr: Expr = andJoin(assertions)
        Some((InoxProgram(locals.symbols), expr))

      case _ =>
        val (newAssertions, newLocals) = extractCommand(cmd)(using locals)
        assertions ++= newAssertions
        locals = newLocals
        None
    }).flatten
  }

  protected class Locals (
    funs: Map[SSymbol, Identifier],
    sorts: Map[SSymbol, Identifier],
    constructors: Map[SSymbol, Identifier],
    selectors: Map[SSymbol, Identifier],
    val vars: Map[SSymbol, Expr],
    tps: Map[SSymbol, TypeParameter],
    val symbols: Symbols) { self =>

    def isSort(sym: SSymbol): Boolean = sorts.isDefinedAt(sym)
    def lookupSort(sym: SSymbol): Option[Identifier] = sorts.get(sym)
    def getSort(sym: SSymbol): Identifier = sorts.get(sym).getOrElse {
      throw new MissformedTIPException("unknown sort " + sym, sym.optPos)
    }

    def isConstructor(sym: SSymbol): Boolean = constructors.isDefinedAt(sym)
    def lookupConstructor(sym: SSymbol): Option[Identifier] = constructors.get(sym)
    def getConstructor(sym: SSymbol): Identifier = constructors.get(sym).getOrElse {
      throw new MissformedTIPException("unknown constructor " + sym, sym.optPos)
    }

    def withSort(sym: SSymbol, id: Identifier): Locals = withSorts(Seq(sym -> id))
    def withSorts(seq: Seq[(SSymbol, Identifier)]): Locals =
      new Locals(funs, sorts ++ seq, constructors, selectors, vars, tps, symbols)

    def withConstructor(sym: SSymbol, id: Identifier): Locals = withConstructors(Seq(sym -> id))
    def withConstructors(seq: Seq[(SSymbol, Identifier)]): Locals =
      new Locals(funs, sorts, constructors ++ seq, selectors, vars, tps, symbols)

    def isSelector(sym: SSymbol): Boolean = selectors.isDefinedAt(sym)
    def getSelector(sym: SSymbol): Identifier = selectors.get(sym).getOrElse {
      throw new MissformedTIPException("unknown ADT selector " + sym, sym.optPos)
    }

    def withSelectors(seq: Seq[(SSymbol, Identifier)]): Locals =
      new Locals(funs, sorts, constructors, selectors ++ seq, vars, tps, symbols)

    def isGeneric(sym: SSymbol): Boolean = tps.isDefinedAt(sym)
    def getGeneric(sym: SSymbol): TypeParameter = tps.get(sym).getOrElse {
      throw new MissformedTIPException("unknown generic type " + sym, sym.optPos)
    }

    def withGeneric(sym: SSymbol, tp: TypeParameter): Locals = withGenerics(Seq(sym -> tp))
    def withGenerics(seq: Seq[(SSymbol, TypeParameter)]): Locals =
      new Locals(funs, sorts, constructors, selectors, vars, tps ++ seq, symbols)

    def isVariable(sym: SSymbol): Boolean = vars.isDefinedAt(sym)
    def getVariable(sym: SSymbol): Expr = vars.get(sym).getOrElse {
      throw new MissformedTIPException("unknown variable " + sym, sym.optPos)
    }

    def withVariable(sym: SSymbol, v: Expr): Locals = withVariables(Seq(sym -> v))
    def withVariables(seq: Seq[(SSymbol, Expr)]): Locals =
      new Locals(funs, sorts, constructors, selectors, vars ++ seq, tps, symbols)

    def isFunction(sym: SSymbol): Boolean = funs.isDefinedAt(sym)
    def getFunction(sym: SSymbol): Identifier = funs.get(sym).getOrElse {
      throw new MissformedTIPException("unknown function " + sym, sym.optPos)
    }

    def withFunction(sym: SSymbol, fd: FunDef): Locals = withFunctions(Seq(sym -> fd))
    def withFunctions(fds: Seq[(SSymbol, FunDef)]): Locals =
      new Locals(funs ++ fds.map(p => p._1 -> p._2.id), sorts, constructors, selectors, vars, tps,
        symbols.withFunctions(fds.map(_._2)))

    def registerSort(sort: ADTSort): Locals = registerSorts(Seq(sort))
    def registerSorts(seq: Seq[ADTSort]): Locals =
      new Locals(funs, sorts, constructors, selectors, vars, tps, symbols.withSorts(seq))

    def withSymbols(symbols: Symbols) = new Locals(funs, sorts, constructors, selectors, vars, tps, symbols)

    object extractor extends TermExtractor(self.symbols)

    def extractTerm(term: Term): Expr = extractor.extractTerm(term)(using this)
    def extractSort(sort: Sort): Type = extractor.extractSort(sort)(using this)
  }

  protected val NoLocals: Locals = new Locals(
    Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, NoSymbols)

  protected object DatatypeInvariantExtractor {
    def unapply(cmd: Command): Option[(Seq[SSymbol], SSymbol, Sort, Term)] = cmd match {
      case DatatypeInvariantPar(syms, s, sort, pred) => Some((syms, s, sort, pred))
      case DatatypeInvariant(s, sort, pred) => Some((Seq.empty, s, sort, pred))
      case _ => None
    }
  }

  protected def extractCommand(cmd: Command)
                              (using locals: Locals): (Option[Expr], Locals) = cmd match {
    case Assert(term) =>
      (Some(locals.extractTerm(term)), locals)

    case AssertPar(tps, term) =>
      val tpsLocals = locals.withGenerics(tps.map(s => s -> TypeParameter.fresh(s.name).setPos(s.optPos)))
      (Some(tpsLocals.extractTerm(term)), locals)

    case DeclareConst(sym, sort) =>
      (None, locals.withVariable(sym,
        Variable.fresh(sym.name, locals.extractSort(sort)).setPos(sym.optPos)))

    case DeclareConstPar(tps, sym, sort) =>
      val tpsLocals = locals.withGenerics(tps.map(s => s -> TypeParameter.fresh(s.name).setPos(s.optPos)))
      (None, locals.withVariable(sym,
        Variable.fresh(sym.name, tpsLocals.extractSort(sort)).setPos(sym.optPos)))

    case DeclareFun(name, sorts, returnSort) =>
      (None, locals.withFunction(name, extractSignature(FunDec(name, sorts.map {
        sort => SortedVar(SSymbol(FreshIdentifier("x").uniqueName).setPos(sort), sort).setPos(sort)
      }, returnSort), Seq.empty)))

    case DeclareFunPar(tps, name, sorts, returnSort) =>
      val tpsLocals = locals.withGenerics(tps.map(s => s -> TypeParameter.fresh(s.name).setPos(s.optPos)))
      (None, locals.withFunction(name, extractSignature(FunDec(name, sorts.map {
        sort => SortedVar(SSymbol(FreshIdentifier("x").uniqueName).setPos(sort), sort).setPos(sort)
      }, returnSort), tps)(using tpsLocals)))

    case DefineFun(funDef) =>
      val fd = extractFunction(funDef, Seq.empty)
      (None, locals.withFunction(funDef.name, fd))

    case DefineFunPar(tps, funDef) =>
      val tpsLocals = locals.withGenerics(tps.map(s => s -> TypeParameter.fresh(s.name).setPos(s.optPos)))
      val fd = extractFunction(funDef, tps)(using tpsLocals)
      (None, locals.withFunction(funDef.name, fd))

    case DefineFunRec(funDef) =>
      val fdsLocals = locals.withFunction(funDef.name, extractSignature(funDef, Seq.empty))
      val fd = extractFunction(funDef, Seq.empty)(using fdsLocals)
      (None, locals.withFunction(funDef.name, fd))

    case DefineFunRecPar(tps, funDef) =>
      val tpsLocals = locals.withGenerics(tps.map(s => s -> TypeParameter.fresh(s.name).setPos(s.optPos)))
      val fdsLocals = tpsLocals.withFunction(funDef.name, extractSignature(funDef, tps)(using tpsLocals))
      val fd = extractFunction(funDef, tps)(using fdsLocals)
      (None, locals.withFunction(funDef.name, fd))

    case DefineFunsRec(funDecs, bodies) =>
      val funDefs = for ((funDec, body) <- funDecs zip bodies) yield {
        SMTFunDef(funDec.name, funDec.params, funDec.returnSort, body)
      }
      val bodyLocals = locals.withFunctions(for (funDef <- funDefs) yield {
        funDef.name -> extractSignature(funDef, Seq.empty)
      })
      (None, locals.withFunctions(for (funDef <- funDefs) yield {
        funDef.name -> extractFunction(funDef, Seq.empty)(using bodyLocals)
      }))

    case DefineFunsRecPar(funDecs, bodies) =>
      val funDefs = for ((funDec, body) <- funDecs zip bodies) yield (funDec match {
        case Left(funDec) => (funDec.tps, SMTFunDef(funDec.name, funDec.params, funDec.returnSort, body))
        case Right(funDec) => (Seq.empty[SSymbol], SMTFunDef(funDec.name, funDec.params, funDec.returnSort, body))
      })
      val bodyLocals = locals.withFunctions(for ((tps, funDef) <- funDefs) yield {
        val tpsLocals = locals.withGenerics(tps.map(s => s -> TypeParameter.fresh(s.name).setPos(s.optPos)))
        funDef.name -> extractSignature(funDef, tps)(using tpsLocals)
      })
      (None, locals.withFunctions(for ((tps, funDef) <- funDefs) yield {
        val tpsLocals = bodyLocals.withGenerics(tps.map(s => s -> TypeParameter.fresh(s.name).setPos(s.optPos)))
        funDef.name -> extractFunction(funDef, tps)(using tpsLocals)
      }))

    case DeclareDatatypesPar(tps, datatypes) =>
      var locs = locals
        .withSorts(datatypes.map { case (sym, _) => sym -> FreshIdentifier(sym.name) })
        .withConstructors(datatypes.flatMap { case (_, conss) =>
          conss.map(c => c.sym -> FreshIdentifier(c.sym.name))
        })

      val generics = tps.map(s => s -> TypeParameter.fresh(s.name).setPos(s.optPos))
      for ((sym, conss) <- datatypes) {
        val adtLocals = locs.withGenerics(generics)
        val children = for (Constructor(sym, fields) <- conss) yield {
          val id = locs.getConstructor(sym)
          val vds = fields.map { case (s, sort) =>
            ValDef.fresh(s.name, adtLocals.extractSort(sort)).setPos(s.optPos)
          }

          (id, vds)
        }

        val allTparams: Set[TypeParameter] = children.flatMap(_._2).toSet.flatMap {
          (vd: ValDef) => typeOps.typeParamsOf(vd.getType(using locs.symbols)): Set[TypeParameter]
        }

        val tparams: Seq[TypeParameterDef] = tps.flatMap { sym =>
          val tp = adtLocals.getGeneric(sym)
          if (allTparams(tp)) Some(TypeParameterDef(tp).setPos(sym.optPos)) else None
        }

        val sortId = adtLocals.getSort(sym)
        locs = locs.registerSort(new ADTSort(sortId, tparams, (conss zip children).map {
          case (cons, (id, vds)) => new ADTConstructor(id, sortId, vds).setPos(cons.sym.optPos)
        }, Seq.empty).setPos(sym.optPos))

        locs = locs.withSelectors((conss zip children).flatMap {
          case (Constructor(_, fields), (_, vds)) => (fields zip vds).map(p => p._1._1 -> p._2.id)
        })
      }

      (None, locs)

    case DeclareSort(sym, arity) =>
      val sortId = FreshIdentifier(sym.name)
      val consId = sortId.freshen
      (None, locals.withSort(sym, sortId).withConstructor(sym, consId).registerSort {
        val tparams = List.range(0, arity).map {
          i => TypeParameterDef(TypeParameter.fresh("A" + i).setPos(sym.optPos)).setPos(sym.optPos)
        }
        val field = ValDef.fresh("val", IntegerType().setPos(sym.optPos)).setPos(sym.optPos)

        new ADTSort(sortId, tparams, Seq(
          new ADTConstructor(consId, sortId, Seq(field)).setPos(sym.optPos)
        ), Seq.empty).setPos(sym.optPos)
      })

    case DatatypeInvariantExtractor(syms, s, sort, pred) =>
      val tps = syms.map(s => TypeParameter.fresh(s.name).setPos(s.optPos))
      val adt = locals.withGenerics(syms zip tps).extractSort(sort) match {
        case adt @ ADTType(id, typeArgs) if tps == typeArgs => adt.getSort(using locals.symbols).definition
        case _ => throw new MissformedTIPException(s"Unexpected type parameters $syms", sort.optPos)
      }

      val vd = ValDef.fresh(s.name, ADTType(adt.id, adt.typeArgs)).setPos(s.optPos)

      val body = locals
        .withGenerics(syms zip adt.typeArgs)
        .withVariable(s, vd.toVariable)
        .extractTerm(pred)

      val (optAdt, fd) = adt.invariant(using locals.symbols) match {
        case Some(fd) =>
          val Seq(v) = fd.params
          val fullBody = and(
            fd.fullBody,
            exprOps.replaceFromSymbols(Map(v.toVariable -> vd.toVariable), body).setPos(body)
          ).setPos(body)
          (None, fd.copy(fullBody = fullBody))

        case None =>
          val id = FreshIdentifier("inv$" + adt.id.name)
          val newAdt = adt.copy(flags = adt.flags :+ HasADTInvariant(id))
          val fd = new FunDef(id, adt.tparams, Seq(vd), BooleanType().setPos(s.optPos), body, Seq.empty).setPos(s.optPos)
          (Some(newAdt), fd)
      }

      (None, locals.withSymbols(
        locals.symbols.withFunctions(Seq(fd)).withSorts(optAdt.toSeq)))

    case _ =>
      throw new MissformedTIPException("unknown TIP command " + cmd, cmd.optPos)
  }

  private def extractSignature(fd: FunDec, tps: Seq[SSymbol])(using locals: Locals): FunDef = {
    assert(!locals.isFunction(fd.name))
    val id = FreshIdentifier(fd.name.name)
    val tparams = tps.map(sym => TypeParameterDef(locals.getGeneric(sym)).setPos(sym.optPos))

    val params = fd.params.map { case SortedVar(s, sort) =>
      ValDef.fresh(s.name, locals.extractSort(sort)).setPos(s.optPos)
    }

    val returnType = locals.extractSort(fd.returnSort)
    val body = Choose(ValDef.fresh("res", returnType), BooleanLiteral(true))

    new FunDef(id, tparams, params, returnType, body, Seq.empty).setPos(fd.name.optPos)
  }

  private def extractSignature(fd: SMTFunDef, tps: Seq[SSymbol])(using locals: Locals): FunDef = {
    extractSignature(FunDec(fd.name, fd.params, fd.returnSort), tps)
  }

  private def extractFunction(fd: SMTFunDef, tps: Seq[SSymbol])(using locals: Locals): FunDef = {
    val sig = if (locals.isFunction(fd.name)) {
      locals.symbols.getFunction(locals.getFunction(fd.name))
    } else {
      extractSignature(fd, tps)
    }

    val bodyLocals = locals
      .withVariables((fd.params zip sig.params).map(p => p._1.name -> p._2.toVariable))
      .withFunctions(if (locals.isFunction(fd.name)) Seq(fd.name -> sig) else Seq.empty)

    val fullBody = bodyLocals.extractTerm(fd.body)

    new FunDef(sig.id, sig.tparams, sig.params, sig.returnType, fullBody, Seq.empty).setPos(fd.name.optPos)
  }

  private def isConstructorSymbol(sym: SSymbol)(using locals: Locals): Option[Identifier] = {
    if (sym.name.startsWith("is-")) {
      val adtSym = SSymbol(sym.name.split("-").tail.mkString("-"))
      locals.lookupConstructor(adtSym)
    } else {
      None
    }
  }

  private def instantiateTypeParams(tps: Seq[TypeParameterDef], formals: Seq[Type], actuals: Seq[Type])
                                   (using locals: Locals): Seq[Type] = {
    assert(formals.size == actuals.size)

    import locals.symbols.{given, _}
    val formal = tupleTypeWrap(formals)
    val actual = tupleTypeWrap(actuals)

    // freshen the type parameters in case we're building a substitution that includes params from `tps`
    val tpSubst: Map[Type, Type] = typeOps.typeParamsOf(actual).map(tp => tp -> tp.freshen).toMap
    val tpRSubst = tpSubst.map(_.swap)
    val substActual = typeOps.replace(tpSubst, actual)

    instantiation(formal, substActual) match {
      case Some(tmap) => tps.map(tpd => tmap.get(tpd.tp).map {
        tpe => typeOps.replace(tpRSubst, tpe)
      }.getOrElse(tpd.tp))

      case None => throw new MissformedTIPException(
        s"could not instantiate $tps in $formals given $actuals",
        actuals.headOption.map(_.getPos).getOrElse(NoPosition)
      )
    }
  }

  class TermExtractor private(override val trees: inox.trees.type,
                              override val symbols: Symbols) extends solvers.smtlib.SMTLIBParser {
    def this(symbols: Symbols) = this(inox.trees, symbols)

    import trees.{given, _}
    import symbols.{given, _}

    protected case class Context(locals: Locals) extends super.AbstractContext {
      val vars = locals.vars
      def withVariable(sym: SSymbol, expr: Expr): Context = Context(locals.withVariable(sym, expr))

      @inline def isSort(sym: SSymbol): Boolean = locals.isSort(sym)
      @inline def lookupSort(sym: SSymbol): Option[Identifier] = locals.lookupSort(sym)
      @inline def getSort(sym: SSymbol): Identifier = locals.getSort(sym)

      @inline def isConstructor(sym: SSymbol): Boolean = locals.isConstructor(sym)
      @inline def lookupConstructor(sym: SSymbol): Option[Identifier] = locals.lookupConstructor(sym)
      @inline def getConstructor(sym: SSymbol): Identifier = locals.getConstructor(sym)

      @inline def isSelector(sym: SSymbol): Boolean = locals.isSelector(sym)
      @inline def getSelector(sym: SSymbol): Identifier = locals.getSelector(sym)

      @inline def isGeneric(sym: SSymbol): Boolean = locals.isGeneric(sym)
      @inline def getGeneric(sym: SSymbol): TypeParameter = locals.getGeneric(sym)

      @inline def isVariable(sym: SSymbol): Boolean = locals.isVariable(sym)
      @inline def getVariable(sym: SSymbol): Expr = locals.getVariable(sym)

      @inline def isFunction(sym: SSymbol): Boolean = locals.isFunction(sym)
      @inline def getFunction(sym: SSymbol): Identifier = locals.getFunction(sym)
    }

    def extractTerm(term: Term)(using locals: Locals): Expr = fromSMT(term)(using Context(locals))
    def extractSort(sort: Sort)(using locals: Locals): Type = fromSMT(sort)(using Context(locals))

    override protected def fromSMT(term: Term, otpe: Option[Type] = None)(using ctx: Context): Expr = (term match {
      case QualifiedIdentifier(SimpleIdentifier(sym), None) if ctx.isVariable(sym) =>
        ctx.getVariable(sym)

      case QualifiedIdentifier(SimpleIdentifier(sym), Some(sort)) if ctx.isVariable(sym) =>
        val v = ctx.getVariable(sym).asInstanceOf[Variable]
        Variable(v.id, fromSMT(sort), v.flags)

      case SMTAssume(pred, body) =>
        Assume(fromSMT(pred), fromSMT(body))

      case SMTChoose(sym, sort, pred) =>
        val vd = ValDef.fresh(sym.name, fromSMT(sort))
        Choose(vd, fromSMT(pred)(using ctx.withVariable(sym, vd.toVariable)))

      case SMTLet(binding, bindings, term) =>
        var context = ctx
        val mapping = for (VarBinding(name, term) <- (binding +: bindings)) yield {
          val e = fromSMT(term)(using context)
          val vd = ValDef.fresh(name.name, e.getType).setPos(name.optPos)
          context = context.withVariable(name, vd.toVariable)
          vd -> e
        }
        mapping.foldRight(fromSMT(term)(using context)) { case ((vd, e), body) => Let(vd, e, body).setPos(vd) }

      case SMTApplication(caller, args) =>
        Application(fromSMT(caller), args.map(fromSMT(_)))

      case SMTLambda(svs, term) =>
        val (vds, bindings) = svs.map { case SortedVar(s, sort) =>
          val vd = ValDef.fresh(s.name, fromSMT(sort)).setPos(s.optPos)
          (vd, s -> vd.toVariable)
        }.unzip
        otpe match {
          case Some(FunctionType(_, to)) => Lambda(vds, fromSMT(term, to)(using ctx.withVariables(bindings)))
          case _ => Lambda(vds, fromSMT(term)(using ctx.withVariables(bindings)))
        }

      case QualifiedIdentifier(SimpleIdentifier(sym), optSort) if ctx.isConstructor(sym) =>
        val cons = symbols.getConstructor(ctx.getConstructor(sym))
        val tps = optSort match {
          case Some(sort) =>
            fromSMT(sort).asInstanceOf[ADTType].tps
          case _ =>
            assert(cons.getSort.tparams.isEmpty)
            Seq.empty
        }
        ADT(cons.id, tps, Seq.empty)

      case FunctionApplication(QualifiedIdentifier(SimpleIdentifier(sym), None), args)
      if ctx.isConstructor(sym) =>
        val es = args.map(fromSMT(_))
        val cons = symbols.getConstructor(ctx.getConstructor(sym))
        val tps = instantiateTypeParams(cons.getSort.tparams, cons.fields.map(_.getType), es.map(_.getType))(using ctx.locals)
        ADT(cons.id, tps, es)

      case QualifiedIdentifier(SimpleIdentifier(sym), optSort) if ctx.isFunction(sym) =>
        val fd = symbols.getFunction(ctx.getFunction(sym))
        val tfd = optSort match {
          case Some(sort) =>
            val tpe = fromSMT(sort)
            val tps = instantiateTypeParams(fd.tparams, Seq(fd.getType), Seq(tpe))(using ctx.locals)
            fd.typed(tps)

          case None =>
            fd.typed
        }
        tfd.applied

      case FunctionApplication(QualifiedIdentifier(SimpleIdentifier(sym), optSort), args)
      if ctx.isFunction(sym) =>
        val es = args.map(fromSMT(_))
        val fd = symbols.getFunction(ctx.getFunction(sym))
        val tps = optSort match {
          case Some(sort) =>
            val tpe = fromSMT(sort)
            instantiateTypeParams(
              fd.tparams,
              fd.params.map(_.getType) :+ fd.getType,
              es.map(_.getType) :+ tpe
            )(using ctx.locals)

          case None =>
            instantiateTypeParams(fd.tparams, fd.params.map(_.getType), es.map(_.getType))(using ctx.locals)
        }
        fd.typed(tps).applied(es)

      case FunctionApplication(QualifiedIdentifier(SimpleIdentifier(sym), None), Seq(term))
      if isConstructorSymbol(sym)(using ctx.locals).isDefined =>
        val e = fromSMT(term)
        IsConstructor(e, isConstructorSymbol(sym)(using ctx.locals).get)

      case FunctionApplication(QualifiedIdentifier(SimpleIdentifier(sym), None), Seq(term))
      if ctx.isSelector(sym) =>
        val id = ctx.getSelector(sym)
        val adt = fromSMT(term)
        ADTSelector(adt, id)

      /* String theory extractors */

      case Strings.Length(s) => StringLength(fromSMT(s))
      case Strings.Concat(e1, e2, es @ _*) =>
        es.foldLeft(StringConcat(fromSMT(e1), fromSMT(e2)).setPos(term.optPos)) {
          (c,e) => StringConcat(c, fromSMT(e)).setPos(term.optPos)
        }

      case Strings.Substring(e, start, end) =>
        SubString(fromSMT(e), fromSMT(start), fromSMT(end))

      case Sets.Union(e1, e2) => SetUnion(fromSMT(e1), fromSMT(e2))
      case Sets.Intersection(e1, e2) => SetIntersection(fromSMT(e1), fromSMT(e2))
      case Sets.Setminus(e1, e2) => SetDifference(fromSMT(e1), fromSMT(e2))
      case Sets.Member(e1, e2) => ElementOfSet(fromSMT(e1), fromSMT(e2))
      case Sets.Subset(e1, e2) => SubsetOf(fromSMT(e1), fromSMT(e2))

      case Sets.EmptySet(sort) => FiniteSet(Seq.empty, fromSMT(sort))
      case Sets.Singleton(e) =>
        val elem = fromSMT(e)
        FiniteSet(Seq(elem), elem.getType)

      case Sets.Insert(set, es @ _*) =>
        es.foldLeft(fromSMT(set))((s,e) => SetAdd(s, fromSMT(e)))

      case Bags.Singleton(k, v) =>
        val key = fromSMT(k)
        FiniteBag(Seq(key -> fromSMT(v)), key.getType)

      case Bags.EmptyBag(sort) => FiniteBag(Seq.empty, fromSMT(sort))
      case Bags.Union(e1, e2) => BagUnion(fromSMT(e1), fromSMT(e2))
      case Bags.Intersection(e1, e2) => BagIntersection(fromSMT(e1), fromSMT(e2))
      case Bags.Difference(e1, e2) => BagDifference(fromSMT(e1), fromSMT(e2))
      case Bags.Multiplicity(e1, e2) => MultiplicityInBag(fromSMT(e1), fromSMT(e2))

      case Bags.Insert(bag, es @ _*) =>
        es.foldLeft(fromSMT(bag))((b,e) => BagAdd(b, fromSMT(e)))

      case MapExtensions.Merge(mask, map1, map2) =>
        MapMerge(fromSMT(mask), fromSMT(map1), fromSMT(map2))

      case Match(s, cases) =>
        val scrut = fromSMT(s)
        val matchCases: Seq[(Option[Expr], Expr)] = cases.map(cse => cse.pattern match {
          case Default =>
            (None, fromSMT(cse.rhs))

          case CaseObject(sym) =>
            val id = ctx.getConstructor(sym)
            (Some(IsConstructor(scrut, id).setPos(sym.optPos)), fromSMT(cse.rhs))

          case CaseClass(sym, args) =>
            val id = ctx.getConstructor(sym)
            val tcons = getConstructor(id, scrut.getType.asInstanceOf[ADTType].tps)
            val bindings = (tcons.fields zip args).map { case (vd, sym) => (sym, vd.id, vd.freshen) }

            val expr = fromSMT(cse.rhs)(using ctx.withVariables(bindings.map(p => p._1 -> p._3.toVariable)))
            val fullExpr = bindings.foldRight(expr) { case ((s, id, vd), e) =>
              val selector = ADTSelector(scrut, id).setPos(s.optPos)
              Let(vd, selector, e).setPos(s.optPos)
            }
            (Some(IsConstructor(scrut, id).setPos(sym.optPos)), fullExpr)
        })

        val (withCond, withoutCond) = matchCases.partition(_._1.isDefined)
        val (ifs, last) = if (withoutCond.size > 1) {
          throw new MissformedTIPException("unexpected multiple defaults in " + term, term.optPos)
        } else if (withoutCond.size == 1) {
          (withCond.map(p => p._1.get -> p._2), withoutCond.head._2)
        } else {
          val wc = withCond.map(p => p._1.get -> p._2)
          (wc.init, wc.last._2)
        }

        ifs.foldRight(last) { case ((cond, body), elze) => IfExpr(cond, body, elze).setPos(cond.getPos) }

      case _ => super.fromSMT(term, otpe)
    }).setPos(term.optPos)

    override protected def fromSMT(sort: Sort)(using ctx: Context): Type = (sort match {
      case Sets.SetSort(base) => SetType(fromSMT(base))
      case Bags.BagSort(base) => BagType(fromSMT(base))
      case Sort(SimpleIdentifier(SSymbol("=>")), params :+ res) => FunctionType(params.map(fromSMT), fromSMT(res))
      case Sort(SimpleIdentifier(sym), Seq()) if ctx.isGeneric(sym) => ctx.getGeneric(sym)
      case Sort(SimpleIdentifier(sym), tps) if ctx.isSort(sym) => ADTType(ctx.getSort(sym), tps.map(fromSMT))
      case _ => super.fromSMT(sort)
    }).setPos(sort.id.symbol.optPos)
  }
}

object Parser {
  def apply(file: File): Parser = Parser(new FileReader(file), Some(file))
  def apply(reader: Reader, file: Option[File] = None): Parser = new Parser(reader, file)
}
