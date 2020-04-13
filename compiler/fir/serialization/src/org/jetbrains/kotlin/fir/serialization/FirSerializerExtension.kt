/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.serialization

import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.fir.declarations.FirCallableMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion

abstract class FirSerializerExtension {
    abstract val stringTable: FirElementAwareStringTable

    abstract val metadataVersion: BinaryVersion

    open fun shouldSerializeNestedClass(nestedClass: FirRegularClass): Boolean = true
    open fun shouldUseTypeTable(): Boolean = false
    open fun shouldUseNormalizedVisibility(): Boolean = false

    open val customClassMembersProducer: ClassMembersProducer?
        get() = null

    interface ClassMembersProducer {
        fun getCallableMembers(klass: FirClass<*>): Collection<FirCallableMemberDeclaration<*>>
    }

}