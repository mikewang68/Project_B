package com.bproject.iam.context;

/** A complete authorized tuple, never three independent sets of identifiers. */
public record ScopeTriple(String projectId, String regionId, String subsystemCode)
    implements Comparable<ScopeTriple>, java.io.Serializable {
  public ScopeTriple {
    identifier(projectId, 64); identifier(regionId, 64); identifier(subsystemCode, 32);
  }
  public static String identifier(String value, int max) {
    if (value == null || value.isEmpty() || value.length() > max || !value.matches("^[A-Za-z0-9_-]+$"))
      throw new IllegalArgumentException("INVALID_SCOPE_IDENTIFIER");
    return value;
  }
  public int compareTo(ScopeTriple other) {
    int c=projectId.compareTo(other.projectId);
    if(c==0)c=regionId.compareTo(other.regionId);
    return c==0?subsystemCode.compareTo(other.subsystemCode):c;
  }
}
