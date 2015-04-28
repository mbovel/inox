/* Copyright 2009-2015 EPFL, Lausanne */

package leon
package solvers.smtlib

import purescala.Common.FreshIdentifier
import purescala.Expressions.{FunctionInvocation, BooleanLiteral, Expr, Implies}
import purescala.Definitions.TypedFunDef
import purescala.Constructors.application
import purescala.DefOps.typedTransitiveCallees
import leon.purescala.ExprOps.matchToIfThenElse
import smtlib.parser.Commands._
import smtlib.parser.Terms._
import smtlib.theories.Core.Equals

trait SMTLIBCVC4QuantifiedTarget extends SMTLIBCVC4Target {
  this: SMTLIBSolver =>

  private val typedFunDefExplorationLimit = 10000

  override def declareFunction(tfd: TypedFunDef): SSymbol = {
    val (funs, exploredAll) = typedTransitiveCallees(Set(tfd), Some(typedFunDefExplorationLimit))
    if (!exploredAll) {
      reporter.warning(
        s"Did not manage to explore the space of typed functions trasitively called from ${tfd.id}. The solver may fail"
      )
    }

    // define-funs-rec does not accept parameterless functions, so we have to treat them differently:
    // we declare-fun each one and assert it is equal to its body
    val (withParams, withoutParams) = funs.toSeq partition( _.params.nonEmpty)

    val parameterlessAssertions = withoutParams filterNot functions.containsA flatMap { tfd =>
      // FIXME: Here we actually want to call super[SMTLIBCVC4Target].declareFunction(tfd),
      // but we inline it to work around a freakish compiler bug
      val id = if (tfd.tps.isEmpty) {
        tfd.id
      } else {
        FreshIdentifier(tfd.id.name)
      }
      sendCommand(DeclareFun(id2sym(id), Seq(), declareSort(tfd.returnType)))
      // Until here, that is.

      functions +=(tfd, id2sym(id))

      val bodyAssert = Assert(Equals(id2sym(id): Term, toSMT(matchToIfThenElse(tfd.body.get))(Map())))

      val specAssert = tfd.postcondition map { post =>
        val term = matchToIfThenElse(Implies(
          tfd.precondition getOrElse BooleanLiteral(true),
          application(post, Seq(FunctionInvocation(tfd, Seq())))
        ))
        Assert(toSMT(term)(Map()))
      }

      Seq(bodyAssert) ++ specAssert
    }

    val seen = withParams filterNot functions.containsA

    val smtFunDecls = seen map { tfd =>
      val id = if (tfd.tps.isEmpty) {
        tfd.id
      } else {
        tfd.id.freshen
      }
      val sym = id2sym(id)
      functions +=(tfd, sym)
      FunDec(
        sym,
        tfd.params map { p => SortedVar(id2sym(p.id), declareSort(p.getType)) },
        declareSort(tfd.returnType)
      )
    }

    val smtBodies = smtFunDecls map { case FunDec(sym, _, _) =>
      val tfd = functions.toA(sym)
      toSMT(matchToIfThenElse(tfd.body.get))(tfd.params.map { p =>
        (p.id, id2sym(p.id): Term)
      }.toMap)
    }

    if (smtFunDecls.nonEmpty) {
      sendCommand(DefineFunsRec(smtFunDecls, smtBodies))
      // Assert contracts for defined functions
      for {
        tfd <- seen
        post <- tfd.postcondition
      } {
        val term = matchToIfThenElse(Implies(
          tfd.precondition getOrElse BooleanLiteral(true),
          application(post, Seq(FunctionInvocation(tfd, tfd.params map { _.toVariable})))
        ))
        sendCommand(Assert(quantifiedTerm(ForAll, term)))
      }
    }

    parameterlessAssertions foreach sendCommand

    functions.toB(tfd)

  }

  // For this solver, we prefer the variables of assert() commands to be exist. quantified instead of free
  override def assertCnstr(expr: Expr) =
    sendCommand(Assert(quantifiedTerm(Exists, expr)))

}