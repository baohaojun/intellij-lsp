package org.jetbrains.plugins.scala.lang.psi.stubs

import org.jetbrains.plugins.scala.lang.psi.api.statements.ScTypeAlias
import org.jetbrains.plugins.scala.lang.psi.stubs.impl.{ScBoundsOwnerStub, ScTypeElementOwnerStub}

/**
 * User: Alexander Podkhalyuzin
 * Date: 18.10.2008
 */
trait ScTypeAliasStub extends ScBoundsOwnerStub[ScTypeAlias]
  with ScTypeElementOwnerStub[ScTypeAlias] with ScMemberOrLocal {

  def isDeclaration: Boolean

  def isStableQualifier: Boolean
}
