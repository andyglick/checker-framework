package org.checkerframework.checker.index.qual;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import org.checkerframework.framework.qual.PolymorphicQualifier;
/**
 * A polymorphic qualifier for the MinLen type system.
 *
 * @checker_framework.manual #index-checker Index Checker
 * @checker_framework.manual #qualifier-polymorphism Qualifier polymorphism
 */
@PolymorphicQualifier(MinLen.class)
@Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
public @interface PolyMinLen {}
