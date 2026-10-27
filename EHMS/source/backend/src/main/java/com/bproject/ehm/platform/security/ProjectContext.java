package com.bproject.ehm.platform.security;

/** Scoped to one request or explicitly bound background operation; never inherited by worker threads. */
public final class ProjectContext {
  private static final ThreadLocal<String> CURRENT=new ThreadLocal<>();
  private ProjectContext(){}
  public static String require(String fallback){
    String value=CURRENT.get();return validate(value==null?fallback:value);
  }
  public static String validate(String value){
    if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))throw new IllegalStateException("Explicit project context required");
    return value;
  }
  public static AutoCloseable bind(String value){
    String previous=CURRENT.get();CURRENT.set(validate(value));
    return ()->{if(previous==null)CURRENT.remove();else CURRENT.set(previous);};
  }
}
