package com.bproject.ehm.platform.audit.application;

import java.lang.annotation.*;

/** Records a real execution, not a fabricated backup/training executor. */
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface AuditedTask {
    String action() default "EXECUTE";
    String menu();
}
