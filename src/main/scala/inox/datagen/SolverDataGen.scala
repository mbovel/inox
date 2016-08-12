/* Copyright 2009-2016 EPFL, Lausanne */

package inox
package datagen

import evaluators._
import solvers._
import utils._

trait SolverDataGen extends DataGenerator { self =>
  import program._
  import program.trees._
  import program.symbols._

  def factory(p: Program { val trees: self.program.trees.type }): SolverFactory { val program: p.type }
  def evaluator(p: Program { val trees: self.program.trees.type }): DeterministicEvaluator { val program: p.type }

  def generate(tpe: Type): FreeableIterator[Expr] = {
    generateFor(Seq(ValDef(FreshIdentifier("tmp"), tpe)),
      BooleanLiteral(true), 20, 20).map(_.head).takeWhile(_ => !interrupted.get)
  }

  def generateFor(ins: Seq[ValDef], satisfying: Expr, maxValid: Int, maxEnumerated: Int): FreeableIterator[Seq[Expr]] = {
    if (ins.isEmpty) {
      FreeableIterator.empty
    } else {

      var cdToId: Map[ClassDef, Identifier] = Map.empty
      var fds: Seq[FunDef] = Seq.empty

      def sizeFor(of: Expr): Expr = bestRealType(of.getType) match {
        case ct: ClassType =>
          val tcd = ct.tcd
          val root = tcd.cd.root
          val id = cdToId.getOrElse(root, {
            import dsl._

            val id = FreshIdentifier("sizeOf", true)
            val tparams = root.tparams.map(_.freshen)
            cdToId += root -> id

            def typed(ccd: CaseClassDef) = TypedCaseClassDef(ccd, tparams.map(_.tp))
            def sizeOfCaseClass(ccd: CaseClassDef, expr: Expr): Expr =
              typed(ccd).fields.foldLeft(IntegerLiteral(1): Expr) { (i, f) =>
                plus(i, sizeFor(expr.getField(f.id)))
              }

            val x = Variable(FreshIdentifier("x", true), tcd.root.toType)
            fds +:= new FunDef(id, tparams, Seq(x.toVal), IntegerType, root match {
              case acd: AbstractClassDef =>
                val (child +: rest) = acd.descendants
                def sizeOf(ccd: CaseClassDef) = sizeOfCaseClass(ccd, x.asInstOf(typed(ccd).toType))
                rest.foldLeft(sizeOf(child)) { (elze, ccd) =>
                  if_ (x.isInstOf(typed(ccd).toType)) { sizeOf(ccd) } else_ { elze }
                }

              case ccd: CaseClassDef =>
                sizeOfCaseClass(ccd, x)
            }, Set.empty)

            id
          })

          FunctionInvocation(id, ct.tps, Seq(of))

        case tt @ TupleType(tps) =>
          val exprs = for ((t,i) <- tps.zipWithIndex) yield {
            sizeFor(tupleSelect(of, i+1, tps.size))
          }

          exprs.foldLeft(IntegerLiteral(1): Expr)(plus)

        case _ =>
          IntegerLiteral(1)
      }

      val sizeOf = sizeFor(tupleWrap(ins.map(_.toVariable)))

      // We need to synthesize a size function for ins' types.
      val pgm1 = program.extend(functions = fds)
      val modelEnum = ModelEnumerator(pgm1)(factory(pgm1), evaluator(pgm1))

      val enum = modelEnum.enumVarying(ins, satisfying, sizeOf, 5)

      enum.take(maxValid).map(model => ins.map(model)).takeWhile(_ => !interrupted.get)
    }
  }
}

object SolverDataGen {
  def apply(p: InoxProgram): SolverDataGen { val program: p.type } = new SolverDataGen {
    val program: p.type = p
    def factory(p: InoxProgram): SolverFactory { val program: p.type } = SolverFactory.default(p)
    def evaluator(p: InoxProgram): RecursiveEvaluator { val program: p.type } = RecursiveEvaluator.default(p)
  }
}