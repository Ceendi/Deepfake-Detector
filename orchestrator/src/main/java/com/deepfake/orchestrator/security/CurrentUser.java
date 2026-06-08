package com.deepfake.orchestrator.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Resolves the current {@link AuthenticatedUser} from the JWT in the security context.
 * Keeps controllers free of {@code SecurityContextHolder}/{@code Jwt} plumbing.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
