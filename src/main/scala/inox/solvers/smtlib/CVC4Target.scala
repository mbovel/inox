/* Copyright 2009-2018 EPFL, Lausanne */

package inox
package solvers
package smtlib

import _root_.smtlib.trees.Terms.{Identifier => SMTIdentifier, _}
import _root_.smtlib.trees.Commands._
import _root_.smtlib.interpreters.CVC4Interpreter
import _root_.smtlib.theories._
import _root_.smtlib.theories.experimental._

trait CVC4Target extends SMTLIBTarget with SMTLIBDebugger {
  import context.{given, _}
  import program._
  import program.trees._
  import program.symbols.{given, _}

  def targetName = "cvc4"

  protected val interpreter = {
    val opts = interpreterOpts
    reporter.debug("Invoking solver with "+opts.mkString(" "))
    new CVC4Interpreter("cvc4", opts.toArray)
  }

  override protected def computeSort(t: Type): Sort = t match {
    case SetType(base) => Sets.SetSort(declareSort(base))
    case _ => super.computeSort(t)
  }

  override protected def fromSMT(t: Term, otpe: Option[Type] = None)(using Context): Expr = {
    (t, otpe) match {
      // EK: This hack is necessary for sygus which does not strictly follow smt-lib for negative literals
      case (SimpleSymbol(SSymbol(v)), Some(IntegerType())) if v.startsWith("-") =>
        try {
          IntegerLiteral(v.toInt)
        } catch {
          case _: Throwable => super.fromSMT(t, otpe)
        }

      // XXX @nv: CVC4 seems to return some weird representations for certain adt selectors
      case (FunctionApplication(SimpleSymbol(s), Seq(e)), _)
      if s.name.endsWith("'") && selectors.containsB(SSymbol(s.name.init)) =>
        fromSMT(FunctionApplication(SimpleSymbol(SSymbol(s.name.init)), Seq(e)), otpe)

      // XXX @nv: CVC4 seems to return some weird representations for certain adt constructors
      case (FunctionApplication(SimpleSymbol(s), args), _)
      if s.name.endsWith("'") && constructors.containsB(SSymbol(s.name.init)) =>
        fromSMT(FunctionApplication(SimpleSymbol(SSymbol(s.name.init)), args), otpe)

      // XXX @nv: CVC4 seems to return bv literals instead of booleans sometimes
      case (FixedSizeBitVectors.BitVectorLit(bs), Some(BooleanType())) if bs.size == 1 =>
        BooleanLiteral(bs.head)
      case (FixedSizeBitVectors.BitVectorConstant(n, size), Some(BooleanType())) if size == 1 =>
        BooleanLiteral(n == 1)
      case (Core.Equals(e1, e2), _) =>
        fromSMTUnifyType(e1, e2, None)(Equals.apply) match {
          case Equals(IsTyped(lhs, BooleanType()), IsTyped(_, BVType(true, 1))) =>
            Equals(lhs, fromSMT(e2, BooleanType()))
          case Equals(IsTyped(_, BVType(true, 1)), IsTyped(rhs, BooleanType())) =>
            Equals(fromSMT(e1, BooleanType()), rhs)
          case expr => expr
        }

      case (Sets.EmptySet(sort), Some(SetType(base))) => FiniteSet(Seq.empty, base)
      case (Sets.EmptySet(sort), _) => FiniteSet(Seq.empty, fromSMT(sort))

      case (Sets.Singleton(e), Some(SetType(base))) => FiniteSet(Seq(fromSMT(e, base)), base)
      case (Sets.Singleton(e), _) =>
        val elem = fromSMT(e)
        FiniteSet(Seq(elem), elem.getType)

      case (Sets.Insert(set, es @ _*), Some(SetType(base))) => es.foldLeft(fromSMT(set, SetType(base))) {
        case (FiniteSet(elems, base), e) =>
          val elem = fromSMT(e, base)
          FiniteSet(elems.filter(_ != elem) :+ elem, base)
        case (s, e) => SetAdd(s, fromSMT(e, base))
      }

      case (Sets.Insert(set, es @ _*), _) => es.foldLeft(fromSMT(set)) {
        case (FiniteSet(elems, base), e) =>
          val elem = fromSMT(e, base)
          FiniteSet(elems.filter(_ != elem) :+ elem, base)
        case (s, e) => SetAdd(s, fromSMT(e))
      }

      case (Sets.Union(e1, e2), Some(SetType(base))) =>
        (fromSMT(e1, SetType(base)), fromSMT(e2, SetType(base))) match {
          case (FiniteSet(elems1, _), FiniteSet(elems2, _)) => FiniteSet(elems1 ++ elems2, base)
          case (s1, s2) => SetUnion(s1, s2)
        }

      case (Sets.Union(e1, e2), _) =>
        (fromSMT(e1), fromSMT(e2)) match {
          case (fs1 @ FiniteSet(elems1, b1), fs2 @ FiniteSet(elems2, b2)) =>
            val tpe = leastUpperBound(b1, b2)
            if (tpe == Untyped) unsupported(SetUnion(fs1, fs2), "woot? incompatible set base-types")
            FiniteSet(elems1 ++ elems2, tpe)
          case (s1, s2) => SetUnion(s1, s2)
        }

      case (ArraysEx.Store(e1, e2, e3), Some(MapType(from, to))) =>
        (fromSMT(e1, MapType(from, to)), fromSMT(e2, from), fromSMT(e3, to)) match {
          case (FiniteMap(elems, default, _, _), key, value) => FiniteMap(elems :+ (key -> value), default, from, to)
          case _ => super.fromSMT(t, otpe)
        }

      case (ArraysEx.Store(e1, e2, e3), _) =>
        (fromSMT(e1), fromSMT(e2), fromSMT(e3)) match {
          case (FiniteMap(elems, default, from, to), key, value) => FiniteMap(elems :+ (key -> value), default, from, to)
          case _ => super.fromSMT(t, otpe)
        }

      case (FunctionApplication(SimpleSymbol(SSymbol("__array_store_all__")), Seq(_, elem)), Some(MapType(k, v))) =>
        FiniteMap(Seq(), fromSMT(elem, v), k, v)

      case _ => super.fromSMT(t, otpe)
    }
  }

  override protected def toSMT(e: Expr)(using bindings: Map[Identifier, Term]) = e match {
    /**
     * ===== Set operations =====
     */
    case fs @ FiniteSet(elems, _) =>
      if (elems.isEmpty) {
        Sets.EmptySet(declareSort(fs.getType))
      } else {
        val selems = elems.map(toSMT)

        if (exprOps.variablesOf(elems.head).isEmpty) {
          val sgt = Sets.Singleton(selems.head)

          if (selems.size > 1) {
            Sets.Insert(selems.tail :+ sgt)
          } else {
            sgt
          }
        } else {
          val sgt = Sets.EmptySet(declareSort(fs.getType))
          Sets.Insert(selems :+ sgt)
        }
      }

    case SubsetOf(ss, s)        => Sets.Subset(toSMT(ss), toSMT(s))
    case ElementOfSet(e, s)     => Sets.Member(toSMT(e), toSMT(s))
    case SetDifference(a, b)    => Sets.Setminus(toSMT(a), toSMT(b))
    case SetUnion(a, b)         => Sets.Union(toSMT(a), toSMT(b))
    case SetAdd(a, b)           => Sets.Insert(toSMT(b), toSMT(a))
    case SetIntersection(a, b)  => Sets.Intersection(toSMT(a), toSMT(b))

    case FiniteMap(_, default, _, _) if !isValue(default) || exprOps.exists {
      case _: Lambda => true
      case _ => false
    } (default) =>
      unsupported(e, "Cannot encode map with non-constant default value")

    case _ =>
      super.toSMT(e)
  }
}
