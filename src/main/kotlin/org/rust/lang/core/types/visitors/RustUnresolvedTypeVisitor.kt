package org.rust.lang.core.types.visitors

import org.rust.lang.core.types.RustUnitType
import org.rust.lang.core.types.RustUnknownType
import org.rust.lang.core.types.unresolved.RustUnresolvedFunctionType
import org.rust.lang.core.types.unresolved.RustUnresolvedTupleType
import org.rust.lang.core.types.unresolved.RustUnresolvedPathType

/**
 * Unresolved types visitor trait
 */
interface RustUnresolvedTypeVisitor<T> {

    fun visitPathType(type: RustUnresolvedPathType): T

    fun visitTupleType(type: RustUnresolvedTupleType): T

    fun visitUnitType(type: RustUnitType): T

    fun visitUnknown(type: RustUnknownType): T

    fun visitFunctionType(type: RustUnresolvedFunctionType): T

}
