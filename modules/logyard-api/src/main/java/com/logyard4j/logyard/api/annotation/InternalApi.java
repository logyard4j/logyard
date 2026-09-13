package com.logyard4j.logyard.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks implementation contracts that may change without compatibility guarantees.
 *
 * <p>Application and extension code must not depend on annotated packages or declarations.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PACKAGE, ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.FIELD})
public @interface InternalApi {
}
