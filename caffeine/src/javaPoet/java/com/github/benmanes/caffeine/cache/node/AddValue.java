/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.node;

import static com.github.benmanes.caffeine.cache.Specifications.vRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;

import java.lang.ref.Reference;
import java.util.Objects;

import javax.lang.model.element.Modifier;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * Adds the value to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AddValue extends NodeRule {

  @Override
  protected boolean applies() {
    return isBaseClass();
  }

  @Override
  protected void execute() {
    context.nodeSubtype
        .addField(newValueField())
        .addMethod(makeGetValue())
        .addMethod(newGetRef("value"))
        .addMethod(makeSetValue())
        .addMethod(makeContainsValue());
    addVarHandle("value", isStrongValues()
        ? ClassName.get(Object.class)
        : valueReferenceType().rawType);
  }

  private FieldSpec newValueField() {
    FieldSpec.Builder fieldSpec = isStrongValues()
        ? FieldSpec.builder(vTypeVar, "value", Modifier.VOLATILE)
        : FieldSpec.builder(valueReferenceType(), "value", Modifier.VOLATILE);
    return fieldSpec.build();
  }

  /** Creates the getValue method. */
  private MethodSpec makeGetValue() {
    MethodSpec.Builder getter = MethodSpec.methodBuilder("getValue")
        .addModifiers(context.publicFinalModifiers())
        .returns(vTypeVar);
    if (valueStrength() == Strength.STRONG) {
      getter.addStatement("return ($T) $L.get(this)", vTypeVar, varHandleName("value"));
      return getter.build();
    }

    CodeBlock code = CodeBlock.builder()
        .beginControlFlow("for (;;)")
            .addStatement("$1T<V> ref = ($1T<V>) $2L.get(this)",
                Reference.class, varHandleName("value"))
            .addStatement("V value = ref.get()")
            .beginControlFlow(
                "if ((value != null) || (ref == $L.getVolatile(this)))", varHandleName("value"))
                .addStatement("return value")
            .endControlFlow()
        .endControlFlow()
        .build();
    return getter.addCode(code).build();
  }

  /** Creates the setValue method. */
  private MethodSpec makeSetValue() {
    MethodSpec.Builder setter = MethodSpec.methodBuilder("setValue")
        .addModifiers(context.publicFinalModifiers())
        .addParameter(vTypeVar, "value")
        .addParameter(vRefQueueType, "referenceQueue");

    if (isStrongValues()) {
      setter.addStatement("$L.set(this, $N)", varHandleName("value"), "value");
    } else {
      setter.addStatement("$1T<V> ref = ($1T<V>) getValueReference()", Reference.class);
      setter.addStatement("$L.set(this, new $T($L, $N, referenceQueue))",
          varHandleName("value"), valueReferenceType(), "getKeyReference()", "value");
      setter.addStatement("ref.clear()");
    }

    return setter.build();
  }

  private MethodSpec makeContainsValue() {
    MethodSpec.Builder containsValue = MethodSpec.methodBuilder("containsValue")
        .addModifiers(context.publicFinalModifiers())
        .addParameter(Object.class, "value")
        .returns(boolean.class);
    if (isStrongValues()) {
      containsValue.addStatement("return $T.equals(value, getValue())", Objects.class);
    } else {
      containsValue.addStatement("return getValue() == value");
    }
    return containsValue.build();
  }
}
