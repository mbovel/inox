/* Copyright 2009-2018 EPFL, Lausanne */

package inox
package solvers.z3

import Z3Native._
import solvers.{z3 => _, _}
import unrolling._
import z3.scala._

abstract class Z3Unrolling(prog: Program,
                           context: Context,
                           enc: transformers.ProgramTransformer {val sourceProgram: prog.type},
                           chooses: ChooseEncoder {val program: prog.type; val sourceEncoder: enc.type})
                          (using semantics: prog.Semantics,
                           semanticsProvider: SemanticsProvider {val trees: enc.targetProgram.trees.type})
  extends AbstractUnrollingSolver(prog, context, enc, chooses)
    (fullEncoder => solvers.theories.Z3(fullEncoder.targetProgram)) { self =>
  import context.{given, _}
  import program._
  import program.trees._
  import program.symbols.{given, _}

  type Encoded = Z3AST

  protected val underlying: AbstractSolver with Z3Native {
    val program: targetProgram.type
    type Trees = Encoded
  }

  protected lazy val z3 = underlying.z3

  override val templates =
    new TemplatesImpl(targetProgram, context)(using targetSemantics)

  private class TemplatesImpl(override val program: targetProgram.type,
                              override val context: Context)
                             (using override val semantics: targetProgram.Semantics)
    extends Templates {

    import program.trees._

    type Encoded = self.Encoded

    def asString(ast: Z3AST): String = ast.toString
    def abort: Boolean = self.abort
    def pause: Boolean = self.pause

    def encodeSymbol(v: Variable): Z3AST = underlying.symbolToFreshZ3Symbol(v)

    def mkEncoder(bindings: Map[Variable, Z3AST])(e: Expr): Z3AST = {
      underlying.toZ3Formula(e, bindings)
    }

    def mkSubstituter(substMap: Map[Z3AST, Z3AST]): Z3AST => Z3AST = {
      val (from, to) = substMap.unzip
      val (fromArray, toArray) = (from.toArray, to.toArray)
      (c: Z3AST) => z3.substitute(c, fromArray, toArray)
    }

    def mkNot(e: Z3AST) = z3.mkNot(e)
    def mkOr(es: Z3AST*) = z3.mkOr(es : _*)
    def mkAnd(es: Z3AST*) = z3.mkAnd(es : _*)
    def mkEquals(l: Z3AST, r: Z3AST) = z3.mkEq(l, r)
    def mkImplies(l: Z3AST, r: Z3AST) = z3.mkImplies(l, r)

    def extractNot(e: Z3AST): Option[Z3AST] = underlying.extractNot(e)

    def decodePartial(e: Z3AST, tpe: Type): Option[Expr] = underlying.asGround(e, tpe)
  }


  protected def declareVariable(v: t.Variable): Z3AST = underlying.declareVariable(v)

  protected def wrapModel(model: Z3Model): ModelWrapper = ModelWrapperImpl(model)

  private case class ModelWrapperImpl(model: Z3Model) extends ModelWrapper {
    private val ex = new underlying.ModelExtractor(model)

    def extractConstructor(v: Z3AST, tpe: t.ADTType): Option[Identifier] = tryZ3Opt(model.eval(v).flatMap {
      elem => z3.getASTKind(elem) match {
        case Z3AppAST(decl, args) if underlying.constructors containsB decl =>
          underlying.constructors.toA(decl) match {
            case underlying.ADTCons(id, _) => Some(id)
            case _ => None
          }
        case _ => None
      }
    })

    def extractSet(v: Z3AST, tpe: t.SetType): Option[Seq[Z3AST]] = tryZ3Opt(model.eval(v).flatMap {
      elem => model.getSetValue(elem) collect { case (set, true) => set.toSeq }
    })

    def extractBag(v: Z3AST, tpe: t.BagType): Option[Seq[(Z3AST, Z3AST)]] = tryZ3Opt(model.eval(v).flatMap {
      elem => model.getArrayValue(elem) flatMap { case (z3Map, z3Default) =>
        z3.getASTKind(z3Default) match {
          case Z3NumeralIntAST(Some(0)) => Some(z3Map.toSeq)
          case _ => None
        }
      }
    })

    def extractMap(v: Z3AST, tpe: t.MapType): Option[(Seq[(Z3AST, Z3AST)], Z3AST)] = tryZ3Opt(model.eval(v).flatMap {
      elem => model.getArrayValue(elem).map(p => p._1.toSeq -> p._2)
    })

    /** WARNING this code is very similar to Z3Native.extractModel!!! */
    def modelEval(elem: Z3AST, tpe: t.Type): Option[t.Expr] = tryZ3Opt(timers.solvers.z3.eval.run {
      tpe match {
        case t.BooleanType() => model.evalAs[Boolean](elem).map(t.BooleanLiteral.apply)

        case t.Int32Type() => model.evalAs[Int](elem).map(t.Int32Literal(_)).orElse {
          model.eval(elem).flatMap(term => ex.get(term, t.Int32Type()))
        }

        /*
         * NOTE The following could be faster than the default case, but be carefull to
         *      fallback to the default when a BigInt doesn't fit in a regular Int.
         *
         * case t.IntegerType() => model.evalAs[Int](elem).map(t.IntegerLiteral(_)).orElse {
         *   model.eval(elem).flatMap(ex.get(_, t.IntegerType()))
         * }
         */

        case other => model.eval(elem).flatMap(ex.get(_, other))
      }
    })

    def getChoose(id: Identifier): Option[t.Expr] = ex.chooses.get(id)

    override def toString = model.toString
  }

  override def push(): Unit = {
    super.push()
    underlying.push()
  }

  override def pop(): Unit = {
    super.pop()
    underlying.pop()
  }

  override def reset(): Unit = {
    super.reset()
    underlying.reset()
  }

  override def interrupt(): Unit = {
    underlying.interrupt()
    super.interrupt()
  }

  override def free(): Unit = {
    super.free()
    underlying.free()
  }
}