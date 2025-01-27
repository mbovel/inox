/* Copyright 2009-2018 EPFL, Lausanne */

package inox
package tip

import smtlib.trees.Terms.{Forall => SMTForall, Identifier => SMTIdentifier, _}
import smtlib.trees.Commands.{Constructor => SMTConstructor, FunDef => SMTFunDef, _}
import smtlib.theories._
import smtlib.theories.experimental._
import smtlib.extensions.tip.Terms.{Application => SMTApplication, Lambda => SMTLambda, _}
import smtlib.extensions.tip.Commands._
import smtlib.Interpreter

import Terms.{Assume => SMTAssume, Choose => SMTChoose}
import Commands._

import java.io.Writer
import scala.collection.mutable.{Map => MutableMap}

import utils._

class Printer private(override val program: InoxProgram,
                      override val context: inox.Context)
                     (override val semantics: program.Semantics,
                      writer: Writer)
  extends solvers.smtlib.ConcreteSMTLIBTarget(program, context)(using semantics) {
  import context.{given, _}
  import program._
  import program.trees._
  import program.symbols.{given, _}

  def this(program: InoxProgram, context: inox.Context, writer: Writer) =
    this(program, context)(program.getSemantics, writer)

  def targetName = "tip"

  protected def unsupported(t: Tree, str: String): Nothing = {
    throw new UnsupportedTree(t, s"(of class ${t.getClass}) is unsupported by TIP printer:\n  " + str)
  }

  /* Note that we are NOT relying on a "real" interpreter here. We just
   * need the printer for calls to [[emit]] to function correctly. */
  protected val interpreter = {
    class PrinterImpl extends smtlib.printer.Printer {
      val name: String = "tip-printer"
      protected def newContext(writer: Writer) = new smtlib.printer.PrintingContext(writer)
    }
    class InterpreterImpl(override val printer: PrinterImpl, override val parser: smtlib.parser.Parser) extends Interpreter {
      def eval(cmd: SExpr): SExpr = {
        printer.printSExpr(cmd, writer)
        writer.write("\n")
        writer.flush()

        smtlib.trees.CommandsResponses.Success
      }

      def free(): Unit = {
        writer.close()
      }

      def interrupt(): Unit = free()
    }
    // the parser should never be used (and is as such set to null)
    new InterpreterImpl(new PrinterImpl, null)
  }

  protected val extraVars = new Bijection[Variable, SSymbol]

  def printScript(expr: Expr): Unit = {
    val tparams = typeOps.typeParamsOf(expr)
    val cmd = if (tparams.nonEmpty) {
      AssertPar(tparams.map(tp => id2sym(tp.id)).toSeq, toSMT(expr)(using Map.empty))
    } else {
      Assert(toSMT(expr)(using Map.empty))
    }

    val invariants = adtManager.types
      .collect { case adt: ADTType => adt }
      .map(_.getSort.definition)
      .flatMap(_.invariant)

    for (fd <- invariants) {
      val Seq(vd) = fd.params
      if (fd.tparams.isEmpty) {
        emit(DatatypeInvariant(
          id2sym(vd.id),
          declareSort(vd.getType),
          toSMT(fd.fullBody)(using Map(vd.id -> id2sym(vd.id)))
        ))
      } else {
        val tps = fd.tparams.map(tpd => declareSort(tpd.tp).id.symbol)
        emit(DatatypeInvariantPar(
          tps,
          id2sym(vd.id),
          declareSort(vd.getType),
          toSMT(fd.fullBody)(using Map(vd.id -> id2sym(vd.id)))
        ))
      }
    }

    emit(cmd)
  }

  def emit(s: String): Unit = writer.write(s)

  protected def liftADTType(adt: ADTType): Type = ADTType(adt.id, adt.getSort.definition.typeArgs)

  protected val tuples: MutableMap[Int, TupleType] = MutableMap.empty

  override protected def computeSort(t: Type): Sort = t match {
    case FunctionType(from, to) =>
      Sort(SimpleIdentifier(SSymbol("=>")), from.map(declareSort) :+ declareSort(to))

    case BagType(base) =>
      Bags.BagSort(declareSort(base))

    case SetType(base) =>
      Sets.SetSort(declareSort(base))

    case StringType() =>
      Strings.StringSort()

    case _ => super.computeSort(t)
  }

  private def getGenericTupleType(n: Int): TupleType = {
    tuples.getOrElse(n, {
      val res = TupleType(List.range(0, n).map(i => TypeParameter.fresh("A" + i)))
      tuples(n) = res
      res
    })
  }

  override protected def declareStructuralSort(t: Type): Sort = t match {
    case adt: ADTType =>
      val tpe = liftADTType(adt)
      adtManager.declareADTs(tpe, declareDatatypes)
      val tpSorts = adt.tps.map(declareSort)
      val Sort(id, _) = sorts.toB(tpe)
      Sort(id, tpSorts)

    case TupleType(ts) =>
      val tpe = getGenericTupleType(ts.size)
      adtManager.declareADTs(tpe, declareDatatypes)
      val tpSorts = ts.map(declareSort)
      Sort(sorts.toB(tpe).id, tpSorts)

    case tp: TypeParameter =>
      Sort(SMTIdentifier(id2sym(tp.id)), Nil)

    case _ => super.declareStructuralSort(t)
  }

  override protected def declareDatatypes(datatypes: Seq[(Type, DataType)]): Unit = {
    val adts = datatypes.filterNot {
      case (_: TypeParameter, _) => true
      case _ => false
    }

    val newAdts: Seq[(Type, DataType)] = adts.map {
      case (ADTType(id, tps), DataType(sym, cases)) =>
        val tsort = getSort(id).typed
        (ADTType(id, tsort.definition.typeArgs), DataType(sym,
          (tsort.constructors zip cases).map {
            case (tcons, Constructor(sym, ADTCons(id, tps), fields)) =>
              Constructor(sym, ADTCons(id, tsort.definition.typeArgs),
                (tcons.fields zip fields).map { case (vd, (id, _)) => (id, vd.getType) })
            case _ =>
              context.reporter.internalError("match should be exhaustive")
          }))

      case (TupleType(tps), DataType(sym, Seq(Constructor(id, TupleCons(_), fields)))) =>
        val TupleType(tparams) = getGenericTupleType(tps.size)
        (TupleType(tparams), DataType(sym, Seq(Constructor(id, TupleCons(tparams),
          (fields zip tparams).map { case ((id, _), tpe) => (id, tpe) }))))

      case p => p
    }.filterNot(p => sorts containsA p._1)

    val generics = (for ((tpe, DataType(id, _)) <- newAdts) yield {
      val tparams: Seq[TypeParameter] = tpe match {
        case ADTType(_, tps) => tps.map(_.asInstanceOf[TypeParameter])
        case TupleType(tps) => tps.map(_.asInstanceOf[TypeParameter])
        case _ => Seq.empty
      }

      val tpSorts = tparams.map(tp => Sort(SMTIdentifier(id2sym(tp.id))))
      sorts += tpe -> Sort(SMTIdentifier(id2sym(id)), tpSorts)
      tparams
    }).flatten

    val genericSyms = generics.map(tp => id2sym(tp.id))

    if (newAdts.nonEmpty) {
      emit(DeclareDatatypesPar(genericSyms,
        (for ((tpe, DataType(sym, cases)) <- newAdts.toList) yield {
          id2sym(sym) -> (for (c <- cases) yield {
            val s = id2sym(c.sym)

            testers += c.tpe -> SSymbol("is-" + s.name)
            constructors += c.tpe -> s

            SMTConstructor(s, c.fields.zipWithIndex.map { case ((cs, t), i) =>
              selectors += (c.tpe, i) -> id2sym(cs)
              (id2sym(cs), declareSort(t))
            })
          }).toList
        }).toList
      ))
    }
  }

  override protected def declareFunction(tfd: TypedFunDef): SSymbol = {
    val fd = tfd.fd

    functions.getB(fd.typed) match {
      case Some(sym) => sym
      case None =>
        functions += fd.typed -> id2sym(fd.id)

        val scc = transitiveCallees(fd.id).filter(id2 => transitivelyCalls(id2, fd.id))
        if (scc.size <= 1) {
          val (sym, params, returnSort, body) = (
            id2sym(fd.id),
            fd.params.map(vd => SortedVar(id2sym(vd.id), declareSort(vd.getType))),
            declareSort(fd.getType),
            toSMT(fd.fullBody)(using fd.params.map(vd => vd.id -> (id2sym(vd.id): Term)).toMap)
          )

          val tps = fd.tparams.map(tpd => declareSort(tpd.tp).id.symbol)

          emit((scc.isEmpty, tps.isEmpty) match {
            case (true, true) => DefineFun(SMTFunDef(sym, params, returnSort, body))
            case (false, true) => DefineFunRec(SMTFunDef(sym, params, returnSort, body))
            case (true, false) => DefineFunPar(tps, SMTFunDef(sym, params, returnSort, body))
            case (false, false) => DefineFunRecPar(tps, SMTFunDef(sym, params, returnSort, body))
          })
        } else {
          functions ++= scc.toList.map(id => getFunction(id).typed -> id2sym(id))

          val (decs, bodies) = (for (id <- scc.toList) yield {
            val fd = getFunction(id)
            val (sym, params, returnSort) = (
              id2sym(id),
              fd.params.map(vd => SortedVar(id2sym(vd.id), declareSort(vd.getType))),
              declareSort(fd.getType)
            )

            val tps = fd.tparams.map(tpd => declareSort(tpd.tp).id.symbol)

            val dec = if (tps.isEmpty) {
              Right(FunDec(sym, params, returnSort))
            } else {
              Left(FunDecPar(tps, sym, params, returnSort))
            }

            val body = toSMT(fd.fullBody)(using fd.params.map(vd => vd.id -> (id2sym(vd.id): Term)).toMap)
            (dec, body)
          }).unzip

          emit(if (decs.exists(_.isLeft)) {
            DefineFunsRecPar(decs, bodies)
          } else {
            DefineFunsRec(decs.map(_.getOrElse(throw new NoSuchElementException("Either.get on Left"))), bodies)
          })
        }

        id2sym(fd.id)
    }
  }

  override protected def toSMT(e: Expr)(using bindings: Map[Identifier, Term]): Term = e match {
    case v @ Variable(id, tp, flags) =>
      val sort = declareSort(tp)
      bindings.get(id) orElse variables.getB(v).map(s => s: Term) getOrElse {
        val tps = typeOps.typeParamsOf(tp).toSeq
        val sym = extraVars.cachedB(v) {
          val sym = id2sym(id)
          emit(if (tps.nonEmpty) {
            DeclareConstPar(tps.map(tp => id2sym(tp.id)), sym, sort)
          } else {
            DeclareConst(sym, sort)
          })
          sym
        }

        if (tps.nonEmpty) QualifiedIdentifier(SMTIdentifier(sym), Some(sort))
        else QualifiedIdentifier(SMTIdentifier(sym), None)
      }

    case Lambda(args, body) =>
      val (newBindings, params) = args.map { vd =>
        val sym = id2sym(vd.id)
        (vd.id -> (sym: Term), SortedVar(sym, declareSort(vd.getType)))
      }.unzip
      SMTLambda(params, toSMT(body)(using bindings ++ newBindings))

    case Forall(args, body) =>
      val (newBindings, param +: params) = args.map { vd =>
        val sym = id2sym(vd.id)
        (vd.id -> (sym: Term), SortedVar(sym, declareSort(vd.getType)))
      }.unzip: @unchecked
      SMTForall(param, params, toSMT(body)(using bindings ++ newBindings))

    case Not(Forall(args, body)) =>
      val (newBindings, param +: params) = args.map { vd =>
        val sym = id2sym(vd.id)
        (vd.id -> (sym: Term), SortedVar(sym, declareSort(vd.getType)))
      }.unzip: @unchecked
      Exists(param, params, toSMT(Not(body))(using bindings ++ newBindings))

    case Application(caller, args) => SMTApplication(toSMT(caller), args.map(toSMT))

    case Assume(pred, body) => SMTAssume(toSMT(pred), toSMT(body))

    case FiniteBag(elems, base) =>
      elems.foldLeft(Bags.EmptyBag(declareSort(base))) { case (b, (k, v)) =>
        Bags.Union(b, Bags.Singleton(toSMT(k), toSMT(v)))
      }

    case BagAdd(bag, elem) => Bags.Insert(toSMT(bag), toSMT(elem))
    case MultiplicityInBag(elem, bag) => Bags.Multiplicity(toSMT(elem), toSMT(bag))
    case BagUnion(b1, b2) => Bags.Union(toSMT(b1), toSMT(b2))
    case BagIntersection(b1, b2) => Bags.Intersection(toSMT(b1), toSMT(b2))
    case BagDifference(b1, b2) => Bags.Difference(toSMT(b1), toSMT(b2))

    case FiniteSet(elems, base) =>
      val empty = Sets.EmptySet(declareSort(base))
      elems match {
        case x :: xs => Sets.Insert(empty, toSMT(x), xs.map(toSMT) : _*)
        case _ => empty
      }

    case SetAdd(set, elem) => Sets.Insert(toSMT(set), toSMT(elem))
    case ElementOfSet(elem, set) => Sets.Member(toSMT(elem), toSMT(set))
    case SubsetOf(s1, s2) => Sets.Subset(toSMT(s1), toSMT(s2))
    case SetIntersection(s1, s2) => Sets.Intersection(toSMT(s1), toSMT(s2))
    case SetUnion(s1, s2) => Sets.Union(toSMT(s1), toSMT(s2))
    case SetDifference(s1, s2) => Sets.Setminus(toSMT(s1), toSMT(s2))

    case MapMerge(mask, map1, map2) =>
      MapExtensions.Merge(toSMT(mask), toSMT(map1), toSMT(map2))

    case StringLiteral(value) => Strings.StringLit(value)
    case StringConcat(s1, s2) => Strings.Concat(toSMT(s1), toSMT(s2))
    case SubString(s, start, end) => Strings.Substring(toSMT(s), toSMT(start), toSMT(end))
    case StringLength(s) => Strings.Length(toSMT(s))

    case adt @ ADT(id, tps, es) =>
      val tcons = adt.getConstructor
      val sort = declareSort(ADTType(tcons.sort.id, tps))
      val constructor = constructors.toB(ADTCons(tcons.id, tcons.sort.definition.typeArgs))
      if (es.isEmpty) {
        if (tcons.tps.nonEmpty) QualifiedIdentifier(SMTIdentifier(constructor), Some(sort))
        else constructor
      } else {
        FunctionApplication(constructor, es.map(toSMT))
      }

    case s @ ADTSelector(e, id) =>
      val cons = s.constructor.definition
      val tpe = ADTType(cons.sort, cons.getSort.typeArgs)
      declareSort(tpe)
      val selector = selectors.toB(ADTCons(cons.id, tpe.tps) -> s.selectorIndex)
      FunctionApplication(selector, Seq(toSMT(e)))

    case IsConstructor(e, id) =>
      val cons = getConstructor(id)
      val tpe = ADTType(cons.sort, cons.getSort.typeArgs)
      declareSort(tpe)
      val tester = testers.toB(ADTCons(cons.id, tpe.tps))
      FunctionApplication(tester, Seq(toSMT(e)))

    case t @ Tuple(es) =>
      declareSort(t.getType)
      val TupleType(tps) = tuples(es.size)
      val constructor = constructors.toB(TupleCons(tps))
      FunctionApplication(constructor, es.map(toSMT))

    case ts @ TupleSelect(t, i) =>
      declareSort(t.getType)
      val TupleType(tps) = tuples(t.getType.asInstanceOf[TupleType].dimension)
      val selector = selectors.toB((TupleCons(tps), i - 1))
      FunctionApplication(selector, Seq(toSMT(t)))

    case fi @ FunctionInvocation(id, tps, args) =>
      val tfd = fi.tfd
      val retTpArgs = typeOps.typeParamsOf(tfd.fd.getType)
      val paramTpArgs = tfd.fd.params.flatMap(vd => typeOps.typeParamsOf(vd.getType)).toSet
      if ((retTpArgs -- paramTpArgs).nonEmpty) {
        val caller = QualifiedIdentifier(
          SMTIdentifier(declareFunction(tfd)),
          Some(declareSort(tfd.getType))
        )
        if (args.isEmpty) caller
        else FunctionApplication(caller, args.map(toSMT))
      } else {
        super.toSMT(e)
      }

    case Choose(vd, pred) =>
      val sym = id2sym(vd.id)
      val sort = declareSort(vd.getType)
      SMTChoose(sym, declareSort(vd.getType), toSMT(pred)(using bindings + (vd.id -> (sym: Term))))

    case _ => super.toSMT(e)
  }
}

