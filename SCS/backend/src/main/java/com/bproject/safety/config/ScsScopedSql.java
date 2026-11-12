package com.bproject.safety.config;

import com.bproject.iam.context.*;
import java.util.*;

/** Applies context to fixed repository SQL only. No endpoint accepts SQL or identifiers. */
public final class ScsScopedSql {
  private ScsScopedSql() {}
  private record Token(String word,int start,int end,int depth) {}
  private record Edit(int start,int end,String replacement) {}
  private static final Set<String> CLAUSES=Set.of("WHERE","ORDER","GROUP","HAVING","LIMIT","OFFSET","FETCH","LEFT","RIGHT","INNER","FULL","OUTER","CROSS","JOIN","ON","UNION","EXCEPT","INTERSECT","FOR","WINDOW","RETURNING","SET");
  private static final Set<String> ALIASES=Set.of("e","q","sa","a","f","n","c","p","o","s","r","d");

  public static String apply(String sql,UserContext context) {
    if(context==null&&!ScsCentralScope.legacyMachineIngress())return sql;
    List<Token> tokens=lex(sql);
    if(tokens.isEmpty())return sql;
    for(int i=0;i<tokens.size()-1;i++)if(tokens.get(i).word().equals(";")&&tokens.get(i).depth()==0)
      throw new IllegalArgumentException("Multiple repository statements are not registered");
    String verb=tokens.get(0).word();
    if(verb.equals("UPDATE"))return update(sql,tokens,context);
    if(verb.equals("DELETE")) {
      for(int i=0;i<tokens.size();i++)if(tokens.get(i).word().equals("FROM")&&rootAt(tokens,i+1)!=null)
        throw new IllegalArgumentException("Physical root deletion is not registered");
      return sql;
    }
    if(!Set.of("SELECT","WITH").contains(verb))return sql;
    if(tokens.stream().anyMatch(t->Set.of("UPDATE","INSERT","DELETE","MERGE").contains(t.word())))
      throw new IllegalArgumentException("Mutating CTE is not registered");
    var edits=new ArrayList<Edit>();
    for(int i=0;i+3<tokens.size();i++) {
      if(!Set.of("FROM","JOIN").contains(tokens.get(i).word()))continue;
      var root=rootAt(tokens,i+1);if(root==null||root==ScsCentralScope.Root.AI_MODEL)continue;
      Token first=tokens.get(i+1),last=tokens.get(i+3);
      int next=i+4;boolean alias=false;
      if(next<tokens.size()&&tokens.get(next).word().equals("AS"))next++;
      if(next<tokens.size()) {
        Token token=tokens.get(next);
        if(token.depth()==last.depth()&&!CLAUSES.contains(token.word())&&token.word().matches("[A-Z_][A-Z0-9_]*")) {
          String name=token.word().toLowerCase(Locale.ROOT);
          if(!ALIASES.contains(name)&&!name.equals(root.table))throw new IllegalArgumentException("Unregistered root alias");
          alias=true;
        }
      }
      String filter=context==null?unassigned("o."):ScopeSql.build(context.scopeMode(),context.activeScope(),ScopeSql.Columns.ORDER).my_info();
      String source="(SELECT * FROM safety."+root.table+" o WHERE 1=1"+filter+ScsMachineRegistry.sqlObjectFilter(root,"o.")+")"+(alias?"":" "+root.table);
      edits.add(new Edit(first.start(),last.end(),source));
    }
    edits.sort(Comparator.comparingInt(Edit::start).reversed());
    var result=new StringBuilder(sql);
    for(Edit edit:edits)result.replace(edit.start(),edit.end(),edit.replacement());
    return result.toString();
  }

  private static String unassigned(String prefix){
    return " AND "+prefix+"project_id IS NULL AND "+prefix+"region_id IS NULL AND "+prefix+"subsystem_code IS NULL ";
  }

  private static String update(String sql,List<Token> tokens,UserContext context) {
    var root=rootAt(tokens,1);if(root==null)return sql;
    if(root==ScsCentralScope.Root.AI_MODEL){
      if(context==null||context.readOnly()||!context.adminLevel().equals("L1_SUPER"))
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"全局模型配置仅允许平台管理员修改");
      return sql;
    }
    if(context!=null&&!context.readOnly()&&context.adminLevel().equals("L1_SUPER")&&context.scopeMode()==ScopeSql.Mode.GLOBAL)return sql;
    if(context!=null&&(context.readOnly()||context.scopeMode()!=ScopeSql.Mode.SCOPED||context.activeScope().size()!=1))
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"写入须选择唯一授权范围");
    // Existing UPDATE predicates are grouped, then constrained in the same SQL
    // statement. The preceding conflict check is not the write authorization.
    int where=-1,end=sql.length();
    for(int i=4;i<tokens.size();i++) {
      Token t=tokens.get(i);if(t.depth()!=0)continue;
      if(t.word().equals("WHERE"))where=t.end();
      if(t.word().equals("RETURNING")||t.word().equals(";")){end=t.start();break;}
    }
    if(where<0)throw new IllegalArgumentException("Unbounded root UPDATE is not registered");
    String filter=context==null?unassigned(""):ScopeSql.build(context.scopeMode(),context.activeScope()).my_info();
    return sql.substring(0,where)+" ("+sql.substring(where,end)+"\n) "+filter+ScsMachineRegistry.sqlObjectFilter(root,"")+sql.substring(end);
  }

  private static ScsCentralScope.Root rootAt(List<Token> tokens,int i) {
    if(i+2>=tokens.size()||!tokens.get(i).word().equals("SAFETY")||!tokens.get(i+1).word().equals("."))return null;
    String table=tokens.get(i+2).word();
    return Arrays.stream(ScsCentralScope.Root.values()).filter(r->r.table.equalsIgnoreCase(table)).findFirst().orElse(null);
  }

  /** Small SQL lexer: literals and comments never become source tokens. */
  private static List<Token> lex(String sql) {
    var result=new ArrayList<Token>();int depth=0;
    for(int i=0;i<sql.length();) {
      char c=sql.charAt(i);if(Character.isWhitespace(c)){i++;continue;}
      if(c=='\'' ){boolean escapes=i>0&&(sql.charAt(i-1)=='E'||sql.charAt(i-1)=='e');i++;boolean closed=false;while(i<sql.length()){if(escapes&&sql.charAt(i)=='\\'){i+=2;continue;}if(sql.charAt(i++)=='\''){if(i<sql.length()&&sql.charAt(i)=='\''){i++;continue;}closed=true;break;}}if(!closed)throw new IllegalArgumentException("Unclosed SQL literal");continue;}
      if(c=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-'){i+=2;while(i<sql.length()&&sql.charAt(i)!='\n')i++;continue;}
      if(c=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'){i+=2;int nesting=1;while(i<sql.length()&&nesting>0){if(i+1<sql.length()&&sql.startsWith("/*",i)){nesting++;i+=2;}else if(i+1<sql.length()&&sql.startsWith("*/",i)){nesting--;i+=2;}else i++;}if(nesting!=0)throw new IllegalArgumentException("Unclosed SQL comment");continue;}
      if(c=='$'){int end=sql.indexOf('$',i+1);if(end>=0&&sql.substring(i+1,end).matches("[A-Za-z_0-9]*")){String tag=sql.substring(i,end+1);int close=sql.indexOf(tag,end+1);if(close<0)throw new IllegalArgumentException("Unclosed dollar SQL literal");i=close+tag.length();continue;}}
      int start=i;
      if(c=='"'){i++;var name=new StringBuilder();boolean closed=false;while(i<sql.length()){char n=sql.charAt(i++);if(n=='"'){if(i<sql.length()&&sql.charAt(i)=='"'){name.append('"');i++;continue;}closed=true;break;}name.append(n);}if(!closed)throw new IllegalArgumentException("Unclosed quoted identifier");result.add(new Token(name.toString().toUpperCase(Locale.ROOT),start,i,depth));continue;}
      if(Character.isLetter(c)||c=='_'){i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||sql.charAt(i)=='_'))i++;result.add(new Token(sql.substring(start,i).toUpperCase(Locale.ROOT),start,i,depth));continue;}
      if(c==')')depth--;
      result.add(new Token(String.valueOf(c),i,i+1,depth));
      if(c=='(')depth++;i++;
    }
    return result;
  }
}
