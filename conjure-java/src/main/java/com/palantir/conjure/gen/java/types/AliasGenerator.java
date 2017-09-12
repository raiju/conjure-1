/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 */

package com.palantir.conjure.gen.java.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.palantir.conjure.defs.types.ConjureType;
import com.palantir.conjure.defs.types.names.ConjurePackage;
import com.palantir.conjure.defs.types.names.ConjurePackages;
import com.palantir.conjure.defs.types.primitive.PrimitiveType;
import com.palantir.conjure.defs.types.reference.AliasTypeDefinition;
import com.palantir.conjure.gen.java.ConjureAnnotations;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;

public final class AliasGenerator {

    private AliasGenerator() {}

    public static JavaFile generateAliasType(
            TypeMapper typeMapper,
            Optional<ConjurePackage> defaultPackage,
            com.palantir.conjure.defs.types.names.TypeName typeName,
            AliasTypeDefinition typeDef) {
        TypeName aliasTypeName = typeMapper.getClassName(typeDef.alias());

        ConjurePackage typePackage = ConjurePackages.getPackage(typeDef.conjurePackage(), defaultPackage, typeName);
        ClassName thisClass = ClassName.get(typePackage.name(), typeName.name());

        TypeSpec.Builder spec = TypeSpec.classBuilder(typeName.name())
                .addAnnotation(ConjureAnnotations.getConjureGeneratedAnnotation(AliasGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addField(aliasTypeName, "value", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(createConstructor(aliasTypeName))
                .addMethod(MethodSpec.methodBuilder("get")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(JsonValue.class)
                        .returns(aliasTypeName)
                        .addStatement("return value")
                        .build())
                .addMethod(MethodSpec.methodBuilder("toString")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(String.class)
                        .addCode(primitiveSafeToString(aliasTypeName))
                        .build())
                .addMethod(MethodSpec.methodBuilder("equals")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .addParameter(TypeName.OBJECT, "other")
                        .returns(TypeName.BOOLEAN)
                        .addCode(primitiveSafeEquality(thisClass, aliasTypeName))
                        .build())
                .addMethod(MethodSpec.methodBuilder("hashCode")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(TypeName.INT)
                        .addCode(primitiveSafeHashCode(aliasTypeName))
                        .build());

        Optional<CodeBlock> maybeValueOfFactoryMethod = valueOfFactoryMethod(typeDef.alias(), thisClass, aliasTypeName);
        if (maybeValueOfFactoryMethod.isPresent()) {
            spec.addMethod(MethodSpec.methodBuilder("valueOf")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter(String.class, "value")
                    .returns(thisClass)
                    .addCode(maybeValueOfFactoryMethod.get())
                    .build());
        }

        spec.addMethod(MethodSpec.methodBuilder("of")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(JsonCreator.class)
                .addParameter(aliasTypeName, "value")
                .returns(thisClass)
                .addStatement("return new $T(value)", thisClass)
                .build());

        if (typeDef.docs().isPresent()) {
            spec.addJavadoc("$L", StringUtils.appendIfMissing(typeDef.docs().get(), "\n"));
        }

        return JavaFile.builder(typePackage.name(), spec.build())
                .skipJavaLangImports(true)
                .indent("    ")
                .build();
    }

    private static Optional<CodeBlock> valueOfFactoryMethod(
            ConjureType conjureType,
            ClassName thisClass,
            TypeName aliasTypeName) {
        if (conjureType instanceof PrimitiveType) {
            return Optional.of(valueOfFactoryMethodForPrimitive((PrimitiveType) conjureType, thisClass, aliasTypeName));
        }
        // TODO(dholanda): delegate to aliased type's valueOf factory method if it exists
        return Optional.empty();
    }

    private static CodeBlock valueOfFactoryMethodForPrimitive(
            PrimitiveType primitiveType,
            ClassName thisClass,
            TypeName aliasTypeName) {
        switch (primitiveType) {
            case STRING:
                return CodeBlock.builder().addStatement("return new $T(value)", thisClass).build();
            case DOUBLE:
                return CodeBlock.builder()
                        .addStatement("return new $T($T.parseDouble(value))", thisClass, aliasTypeName.box()).build();
            case INTEGER:
                return CodeBlock.builder()
                        .addStatement("return new $T($T.parseInt(value))", thisClass, aliasTypeName.box()).build();
            case BOOLEAN:
                return CodeBlock.builder()
                        .addStatement("return new $T($T.parseBoolean(value))", thisClass, aliasTypeName.box()).build();
            case SAFELONG:
            case RID:
            case BEARERTOKEN:
                return CodeBlock.builder()
                        .addStatement("return new $T($T.valueOf(value))", thisClass, aliasTypeName).build();
            default:
                throw new IllegalStateException("Unknown primitive type: " + primitiveType);
        }
    }

    private static MethodSpec createConstructor(TypeName aliasTypeName) {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(aliasTypeName, "value");
        if (!aliasTypeName.isPrimitive()) {
            builder.addStatement("$T.requireNonNull(value, \"value cannot be null\")", Objects.class);
        }
        return builder
                .addStatement("this.value = value")
                .build();
    }

    private static CodeBlock primitiveSafeEquality(ClassName thisClass, TypeName aliasTypeName) {
        if (aliasTypeName.isPrimitive()) {
            return CodeBlocks.statement(
                    "return this == other || (other instanceof $1T && this.value == (($1T) other).value)",
                    thisClass);
        }
        return CodeBlocks.statement(
                "return this == other || (other instanceof $1T && this.value.equals((($1T) other).value))",
                thisClass);
    }

    private static CodeBlock primitiveSafeToString(TypeName aliasTypeName) {
        if (aliasTypeName.isPrimitive()) {
            return CodeBlocks.statement("return $T.valueOf(value)", String.class);
        }
        return CodeBlocks.statement("return value.toString()");
    }

    private static CodeBlock primitiveSafeHashCode(TypeName aliasTypeName) {
        if (aliasTypeName.isPrimitive()) {
            return CodeBlocks.statement("return $T.hashCode(value)", aliasTypeName.box());
        }
        return CodeBlocks.statement("return value.hashCode()");
    }
}
