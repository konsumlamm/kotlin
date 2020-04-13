/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.serialization

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.transformSuspendFunctionToRuntimeFunctionType
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.symbols.StandardClassIds
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.VersionRequirement
import org.jetbrains.kotlin.metadata.serialization.Interner
import org.jetbrains.kotlin.metadata.serialization.MutableTypeTable
import org.jetbrains.kotlin.metadata.serialization.MutableVersionRequirementTable
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.RequireKotlinConstants
import org.jetbrains.kotlin.resolve.constants.EnumValue
import org.jetbrains.kotlin.resolve.constants.IntValue
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.serialization.deserialization.ProtoEnumFlags

class FirElementSerializer private constructor(
    private val typeParameters: Interner<FirTypeParameter>,
    private val extension: FirSerializerExtension,
    val typeTable: MutableTypeTable,
    private val versionRequirementTable: MutableVersionRequirementTable?,
) {
    fun classProto(klass: FirClass<*>): ProtoBuf.Class.Builder {
        val builder = ProtoBuf.Class.newBuilder()

        val regularClass = klass as? FirRegularClass
        val modality = regularClass?.modality ?: Modality.FINAL
        val flags = Flags.getClassFlags(
            klass.annotations.isNotEmpty(),
            ProtoEnumFlags.visibility(regularClass?.let { normalizeVisibility(it) } ?: Visibilities.LOCAL),
            ProtoEnumFlags.modality(modality),
            ProtoEnumFlags.classKind(klass.classKind, regularClass?.isCompanion == true),
            regularClass?.isInner == true,
            regularClass?.isData == true,
            regularClass?.isExternal == true,
            regularClass?.isExpect == true,
            regularClass?.isInline == true,
            false // TODO: klass.isFun not supported yet
        )
        if (flags != builder.flags) {
            builder.flags = flags
        }

        builder.fqName = getClassifierId(klass)

        for (typeParameter in klass.typeParameters) {
            if (typeParameter !is FirTypeParameter) continue
            builder.addTypeParameter(typeParameterProto(typeParameter))
        }

        val classId = klass.symbol.classId
        if (classId != StandardClassIds.Any && classId != StandardClassIds.Nothing) {
            // Special classes (Any, Nothing) have no supertypes
            for (superTypeRef in klass.superTypeRefs) {
                if (useTypeTable()) {
                    builder.addSupertypeId(typeId(superTypeRef))
                } else {
                    builder.addSupertype(typeProto(superTypeRef))
                }
            }
        }

        if (regularClass != null && regularClass.classKind != ClassKind.ENUM_ENTRY) {
            for (constructor in regularClass.declarations.filterIsInstance<FirConstructor>()) {
                builder.addConstructor(constructorProto(constructor))
            }
        }

        val callableMembers =
            extension.customClassMembersProducer?.getCallableMembers(klass)
                ?: klass.declarations.filterIsInstance<FirCallableMemberDeclaration<*>>()

        for (declaration in callableMembers) {
            when (declaration) {
                is PropertyDescriptor -> propertyProto(declaration)?.let { builder.addProperty(it) }
                is FunctionDescriptor -> functionProto(declaration)?.let { builder.addFunction(it) }
            }
        }

        val nestedClassifiers = klass.declarations.filterIsInstance<FirClassLikeDeclaration<*>>()
        for (nestedClassifier in nestedClassifiers) {
            if (nestedClassifier is FirTypeAlias) {
                typeAliasProto(nestedClassifier)?.let { builder.addTypeAlias(it) }
            } else if (nestedClassifier is FirRegularClass) {
                if (!extension.shouldSerializeNestedClass(nestedClassifier)) {
                    continue
                }

                val name = getSimpleNameIndex(nestedClassifier.name)
                if (DescriptorUtils.isEnumEntry(nestedClassifier)) {
                    builder.addEnumEntry(enumEntryProto(nestedClassifier as ClassDescriptor))
                } else {
                    builder.addNestedClassName(name)
                }
            }
        }

        if (modality == Modality.SEALED) {
            for (sealedSubclass in nestedClassifiers.filterIsInstance<FirRegularClass>()) {
                builder.addSealedSubclassFqName(getClassifierId(sealedSubclass))
            }
        }

        val companionObject = regularClass?.companionObject
        if (companionObject != null) {
            builder.companionObjectName = getSimpleNameIndex(companionObject.name)
        }

        val typeTableProto = typeTable.serialize()
        if (typeTableProto != null) {
            builder.typeTable = typeTableProto
        }

        if (versionRequirementTable == null) error("Version requirements must be serialized for classes: $classDescriptor")

        builder.addAllVersionRequirement(versionRequirementTable.serializeVersionRequirements(classDescriptor))

        extension.serializeClass(klass, builder, versionRequirementTable, this)

        writeVersionRequirementForInlineClasses(klass, builder, versionRequirementTable)

        val versionRequirementTableProto = versionRequirementTable.serialize()
        if (versionRequirementTableProto != null) {
            builder.versionRequirementTable = versionRequirementTableProto
        }
        return builder
    }

    private fun typeParameterProto(typeParameter: FirTypeParameter): ProtoBuf.TypeParameter.Builder {
        val builder = ProtoBuf.TypeParameter.newBuilder()

        builder.id = getTypeParameterId(typeParameter)

        builder.name = getSimpleNameIndex(typeParameter.name)

        if (typeParameter.isReified != builder.reified) {
            builder.reified = typeParameter.isReified
        }

        val variance = variance(typeParameter.variance)
        if (variance != builder.variance) {
            builder.variance = variance
        }
        extension.serializeTypeParameter(typeParameter, builder)

        val upperBounds = typeParameter.bounds
        if (upperBounds.size == 1 && KotlinBuiltIns.isDefaultBound(upperBounds.single())) return builder

        for (upperBound in upperBounds) {
            if (useTypeTable()) {
                builder.addUpperBoundId(typeId(upperBound))
            } else {
                builder.addUpperBound(typeProto(upperBound))
            }
        }

        return builder
    }

    fun typeId(typeRef: FirTypeRef): Int = typeId((typeRef as? FirResolvedTypeRef)?.type)

    fun typeId(type: ConeKotlinType): Int = typeTable[typeProto(type)]

    internal fun typeProto(typeRef: FirTypeRef): ProtoBuf.Type.Builder {
        if (typeRef !is FirResolvedTypeRef) {
            val builder = ProtoBuf.Type.newBuilder()
            extension.serializeErrorType(typeRef, builder)
            return builder
        }
        return typeProto(typeRef.type)
    }

    internal fun typeProto(type: ConeKotlinType): ProtoBuf.Type.Builder {
        val builder = ProtoBuf.Type.newBuilder()

        if (type is ConeKotlinErrorType) {
            extension.serializeErrorType(type, builder)
            return builder
        }

        if (type is ConeFlexibleType) {
            val lowerBound = typeProto(type.lowerBound)
            val upperBound = typeProto(type.upperBound)
            extension.serializeFlexibleType(type, lowerBound, upperBound)
            if (useTypeTable()) {
                lowerBound.flexibleUpperBoundId = typeTable[upperBound]
            } else {
                lowerBound.setFlexibleUpperBound(upperBound)
            }
            return lowerBound
        }

        if (type.isSuspendFunctionType) {
            val functionType = typeProto(transformSuspendFunctionToRuntimeFunctionType(type, extension.releaseCoroutines()))
            functionType.flags = Flags.getTypeFlags(true)
            return functionType
        }

        when (val descriptor = type.constructor.declarationDescriptor) {
            is ClassDescriptor, is TypeAliasDescriptor -> {
                val possiblyInnerType = type.buildPossiblyInnerType() ?: error("possiblyInnerType should not be null: $type")
                fillFromPossiblyInnerType(builder, possiblyInnerType)
            }
            is TypeParameterDescriptor -> {
                if (descriptor.containingDeclaration === containingDeclaration) {
                    builder.typeParameterName = getSimpleNameIndex(descriptor.name)
                } else {
                    builder.typeParameter = getTypeParameterId(descriptor)
                }

                assert(type.arguments.isEmpty()) { "Found arguments for type constructor build on type parameter: $descriptor" }
            }
        }

        if (type.isMarkedNullable != builder.nullable) {
            builder.nullable = type.isMarkedNullable
        }

        val abbreviation = type.getAbbreviatedType()?.abbreviation
        if (abbreviation != null) {
            if (useTypeTable()) {
                builder.abbreviatedTypeId = typeId(abbreviation)
            } else {
                builder.setAbbreviatedType(typeProto(abbreviation))
            }
        }

        extension.serializeType(type, builder)

        return builder
    }

    private val stringTable: FirElementAwareStringTable
        get() = extension.stringTable

    private fun useTypeTable(): Boolean = extension.shouldUseTypeTable()

    private fun MutableVersionRequirementTable.serializeVersionRequirements(declaration: FirMemberDeclaration): List<Int> =
        declaration.annotations
            .filter {
                val annotationConeType = it.annotationTypeRef.coneTypeSafe<ConeClassLikeType>()
                annotationConeType?.lookupTag?.classId?.asSingleFqName() == RequireKotlinConstants.FQ_NAME
            }
            .mapNotNull(::serializeVersionRequirementFromRequireKotlin)
            .map(::get)

    private fun serializeVersionRequirementFromRequireKotlin(annotation: FirAnnotationCall): ProtoBuf.VersionRequirement.Builder? {
        val args = annotation.argumentList

        val versionString = (args[RequireKotlinConstants.VERSION] as? StringValue)?.value ?: return null
        val matchResult = RequireKotlinConstants.VERSION_REGEX.matchEntire(versionString) ?: return null

        val major = matchResult.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minor = matchResult.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val patch = matchResult.groupValues.getOrNull(4)?.toIntOrNull() ?: 0

        val proto = ProtoBuf.VersionRequirement.newBuilder()
        VersionRequirement.Version(major, minor, patch).encode(
            writeVersion = { proto.version = it },
            writeVersionFull = { proto.versionFull = it }
        )

        val message = (args[RequireKotlinConstants.MESSAGE] as? StringValue)?.value
        if (message != null) {
            proto.message = stringTable.getStringIndex(message)
        }

        when ((args[RequireKotlinConstants.LEVEL] as? EnumValue)?.enumEntryName?.asString()) {
            DeprecationLevel.ERROR.name -> {
                // ERROR is the default level
            }
            DeprecationLevel.WARNING.name -> proto.level = ProtoBuf.VersionRequirement.Level.WARNING
            DeprecationLevel.HIDDEN.name -> proto.level = ProtoBuf.VersionRequirement.Level.HIDDEN
        }

        when ((args[RequireKotlinConstants.VERSION_KIND] as? EnumValue)?.enumEntryName?.asString()) {
            ProtoBuf.VersionRequirement.VersionKind.LANGUAGE_VERSION.name -> {
                // LANGUAGE_VERSION is the default kind
            }
            ProtoBuf.VersionRequirement.VersionKind.COMPILER_VERSION.name ->
                proto.versionKind = ProtoBuf.VersionRequirement.VersionKind.COMPILER_VERSION
            ProtoBuf.VersionRequirement.VersionKind.API_VERSION.name ->
                proto.versionKind = ProtoBuf.VersionRequirement.VersionKind.API_VERSION
        }

        val errorCode = (args[RequireKotlinConstants.ERROR_CODE] as? IntValue)?.value
        if (errorCode != null && errorCode != -1) {
            proto.errorCode = errorCode
        }

        return proto
    }


    private fun normalizeVisibility(declaration: FirMemberDeclaration): Visibility {
        // It can be necessary for Java classes serialization having package-private visibility
        return if (extension.shouldUseNormalizedVisibility()) declaration.visibility.normalize()
        else declaration.visibility
    }

    private fun getClassifierId(declaration: FirClassLikeDeclaration<*>): Int =
        stringTable.getFqNameIndex(declaration)

    private fun getTypeParameterId(typeParameter: FirTypeParameter): Int =
        typeParameters.intern(typeParameter)
}