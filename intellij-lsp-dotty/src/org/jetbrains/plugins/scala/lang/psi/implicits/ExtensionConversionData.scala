package org.jetbrains.plugins.scala.lang.psi.implicits

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.ResolveState
import org.jetbrains.plugins.scala.lang.psi.ElementScope
import org.jetbrains.plugins.scala.lang.psi.api.InferUtil.extractImplicitParameterType
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.types.api.{FunctionType, TypeParameter, ValType}
import org.jetbrains.plugins.scala.lang.psi.types.{ScType, ScUndefinedSubstitutor}
import org.jetbrains.plugins.scala.lang.resolve.processor.{BaseProcessor, MethodResolveProcessor, ResolveProcessor}
import org.jetbrains.plugins.scala.lang.resolve.{ResolveTargets, ScalaResolveResult}

import scala.collection.{Seq, Set}

/**
  * @author Nikolay.Tropin
  */
case class ExtensionConversionData(baseExpr: ScExpression,
                                   ref: ScExpression,
                                   refName: String,
                                   processor: BaseProcessor,
                                   noApplicability: Boolean,
                                   withoutImplicitsForArgs: Boolean) {

  //TODO! remove this after find a way to improve implicits according to compiler.
  val isHardCoded: Boolean = refName == "+" &&
    baseExpr.getTypeWithoutImplicits().exists {
      _.isInstanceOf[ValType]
    }
  val kinds: Set[ResolveTargets.Value] = processor.kinds
}

object ExtensionConversionHelper {
  def specialExtractParameterType(resolveResult: ScalaResolveResult): Option[(ScType, Seq[TypeParameter])] = {
    val result = extractImplicitParameterType(resolveResult).flatMap {
      case functionType@FunctionType(_, _) => Some(functionType)
      case implicitParameterType =>
        implicit val project = resolveResult.element.getProject
        ElementScope(project).cachedFunction1Type
          .flatMap { functionType =>
            val (_, substitutor) = implicitParameterType.conforms(functionType, ScUndefinedSubstitutor())
            substitutor.getSubstitutor.map {
              _.subst(functionType)
            }.map {
              _.removeUndefines()
            }
          }
    }

    result.collect {
      case FunctionType(tp, _) => (tp, resolveResult.unresolvedTypeParameters.toSeq.flatten)
    }
  }

  def extensionConversionCheck(data: ExtensionConversionData, candidate: Candidate): Option[Candidate] = {
    ProgressManager.checkCanceled()
    import data._

    val resolveResult = candidate._1
    specialExtractParameterType(resolveResult).map {
      _._1
    }.filter {
      case _: ValType if isHardCoded => false
      case _ => true
    }.filter {
      checkHasMethodFast(data, _)
    }.flatMap { tp =>
      if (!noApplicability && processor.isInstanceOf[MethodResolveProcessor]) {
        val typeParams = resolveResult match {
          case ScalaResolveResult(function: ScFunction, _) if function.hasTypeParameters =>
            function.typeParameters.map {
              TypeParameter(_)
            }
          case _ => Seq.empty
        }

        findInType(tp, data, typeParams).map { tp =>
          typeParams match {
            case Seq() => candidate
            case _ => update(candidate, tp)
          }
        }
      }
      else Some(candidate)
    }
  }

  private def update(candidate: Candidate, foundInType: ScalaResolveResult): Candidate = {
    val (candidateResult, candidateSubstitutor) = candidate

    foundInType.resultUndef.flatMap {
      _.getSubstitutor
    }.map { substitutor =>
      val result = candidateResult.copy(subst = foundInType.substitutor.followed(substitutor),
        implicitParameterType = candidateResult.implicitParameterType.map(substitutor.subst))

      (result, candidateSubstitutor.followed(substitutor))
    }.getOrElse {
      candidate
    }
  }

  private def findInType(tp: ScType, data: ExtensionConversionData, typeParams: Seq[TypeParameter]): Option[ScalaResolveResult] = {
    import data._

    Option(processor).collect {
      case processor: MethodResolveProcessor => processor
    }.map { processor =>
      new MethodResolveProcessor(
        ref, refName, processor.argumentClauses, processor.typeArgElements, typeParams, kinds,
        processor.expectedOption, processor.isUnderscore, processor.isShapeResolve, processor.constructorResolve,
        noImplicitsForArgs = withoutImplicitsForArgs)
    }.flatMap { processor =>
      processor.processType(tp, baseExpr, ResolveState.initial)
      processor.candidatesS.find(_.isApplicable())
    }
  }

  private def checkHasMethodFast(data: ExtensionConversionData, tp: ScType) = {
    import data._

    val newProc = new ResolveProcessor(kinds, ref, refName)
    newProc.processType(tp, baseExpr, ResolveState.initial)
    newProc.candidatesS.nonEmpty
  }
}
