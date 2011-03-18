package org.testng.eclipse.ui.conversion;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.testng.eclipse.collections.Maps;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * A rewriter that will convert the current JUnit file to TestNG
 * using JDK5 annotations
 *
 * @author C�dric Beust <cedric@beust.com>
 */
public class AnnotationRewriter implements IRewriteProvider
{
  private static final Set<String> IMPORTS_TO_REMOVE = new HashSet<String>() {{
    add("junit.framework.Assert");
    add("junit.framework.Test");
    add("junit.framework.TestCase");
    add("junit.framework.TestSuite");
    add("org.junit.After");
    add("org.junit.Before");
    add("org.junit.Test");
  }};
  private static final Set<String> STATIC_IMPORTS_TO_REMOVE = new HashSet<String>() {{
    add("org.junit.Assert");
  }};

  public ASTRewrite createRewriter(CompilationUnit astRoot,
      AST ast,
      JUnitVisitor visitor
      ) 
  {
    final ASTRewrite result = ASTRewrite.create(astRoot.getAST());

    //
    // Remove some JUnit imports.
    //
    List<ImportDeclaration> oldImports = visitor.getJUnitImports();
    for (int i = 0; i < oldImports.size(); i++) {
      Name importName = oldImports.get(i).getName();
      String fqn = importName.getFullyQualifiedName();
      if (IMPORTS_TO_REMOVE.contains(fqn)) {
        result.remove((ImportDeclaration) oldImports.get(i), null);
      }
      for (String s : STATIC_IMPORTS_TO_REMOVE) {
        if (fqn.contains(s)) {
          result.remove((ImportDeclaration) oldImports.get(i), null);
        }
      }
    }
    
    //
    // Add imports as needed
    //
    maybeAddImport(ast, result, astRoot, visitor.hasAsserts(), "org.testng.AssertJUnit");
    maybeAddImport(ast, result, astRoot, visitor.hasFail(), "org.testng.Assert");
    maybeAddImport(ast, result, astRoot, !visitor.getBeforeMethods().isEmpty(),
        "org.testng.annotations.BeforeMethod");
    maybeAddImport(ast, result, astRoot, visitor.hasTestMethods(), "org.testng.annotations.Test");
    maybeAddImport(ast, result, astRoot, !visitor.getAfterMethods().isEmpty(),
        "org.testng.annotations.AfterMethod");

    //
    // Add static imports
    //
    Set<String> staticImports = visitor.getStaticImports();
    for (String si : staticImports) {
      addImport(ast, result, astRoot, "org.testng.AssertJUnit." + si, true /* static import */);
    }

    //
    // Remove "extends TestCase"
    //
    SimpleType td = visitor.getTestCase();
    if (null != td) {
      result.remove(td, null);
    }

    //
    // Addd the annotations as needed
    //
    maybeAddAnnotations(ast, visitor, result, visitor.getTestMethods(), "Test", null, null);
    maybeAddAnnotations(ast, visitor, result, visitor.getDisabledTestMethods(), "Test", null,
        createDisabledAttribute(ast));
    maybeAddAnnotations(ast, visitor, result, visitor.getBeforeMethods(), "BeforeMethod",
        "@Before" /* annotation to remove */);
    maybeAddAnnotations(ast, visitor, result, visitor.getAfterMethods(), "AfterMethod",
        "@After" /* annotation to remove */);

    //
    // Remove the suite() method, if any
    //
    MethodDeclaration suiteMethod = visitor.getSuite();
    if (suiteMethod != null) {
      result.remove(suiteMethod, null);
    }

    //
    // Remove all the nodes that need to be removed
    //
    for (ASTNode n : visitor.getNodesToRemove()) {
      result.remove(n, null);
    }

    //
    // Replace "Assert" with "AssertJUnit", unless the method is already imported statically.
    //
    Set<MethodInvocation> asserts = visitor.getAsserts();
    for (MethodInvocation m : asserts) {
      if (! staticImports.contains(m.getName().toString())) {
        Expression exp = m.getExpression();
        Name name = ast.newName("AssertJUnit");
        if (exp != null) {
          result.replace(exp, name, null);
        } else {
          result.set(m, MethodInvocation.EXPRESSION_PROPERTY, name, null);
        }
      }
    }

    //
    // Replace "fail()" with "Assert.fail()"
    //
    for (MethodInvocation fail : visitor.getFails()) {
      SimpleName exp = ast.newSimpleName("Assert");
      result.set(fail, MethodInvocation.EXPRESSION_PROPERTY, exp, null);
    }

    //
    // Replace @Test(expected) with @Test(expectedExceptions)
    // and @Test(timeout) with @Test(timeOut)
    //
    for (Map.Entry<MemberValuePair, String> pair : visitor.getTestsWithExpected().entrySet()) {
      result.replace(pair.getKey().getName(), ast.newSimpleName(pair.getValue()), null);
    }

    //
    // Remove super invocation in the constructor
    //
    SuperConstructorInvocation sci = visitor.getSuperConstructorInvocation();
    if (sci != null) {
      result.remove(sci, null);
    }

    return result;
  }

  private Map<String, Boolean> createDisabledAttribute(AST ast) {
    Map<String, Boolean> result = Maps.newHashMap();
    result.put("enabled", false);
    return result;
  }

  private void maybeAddImport(AST ast, ASTRewrite rewriter, CompilationUnit astRoot, boolean add,
      String imp) {
    if (add) {
      addImport(ast, rewriter, astRoot, imp);
    }
  }

  private void addImport(AST ast, ASTRewrite rewriter, CompilationUnit astRoot, String imp) {
    addImport(ast, rewriter, astRoot, imp, false /* non static import */);
  }

  private void addImport(AST ast, ASTRewrite rewriter, CompilationUnit astRoot, String imp,
      boolean isStatic) {
    ListRewrite lr = rewriter.getListRewrite(astRoot, CompilationUnit.IMPORTS_PROPERTY);
    ImportDeclaration id = ast.newImportDeclaration();
    id.setStatic(isStatic);
    id.setName(ast.newName(imp));
    lr.insertFirst(id, null);
  }

  /**
   * Add the given annotation if the method is non null
   */
  private void maybeAddAnnotation(AST ast, JUnitVisitor visitor, ASTRewrite rewriter,
      MethodDeclaration method, String annotation, String annotationToRemove,
      Map<String, Boolean> attributes)
  {
    if (method != null) {
      addAnnotation(ast, visitor, rewriter, method, createAnnotation(ast, annotation, attributes),
          annotationToRemove);
    }
  }

  /**
   * @return a NormalAnnotation if the annotation to create has attributes or a
   * MarkerAnnotation otherwise.
   */
  private Annotation createAnnotation(AST ast, String name, Map<String, Boolean> attributes) {
    Annotation result = null;
    NormalAnnotation normalAnnotation = null;
    if (attributes != null && attributes.size() > 0) {
      normalAnnotation = ast.newNormalAnnotation();
      result = normalAnnotation;
    } else {
      result = ast.newMarkerAnnotation();
    }
    result.setTypeName(ast.newName(name));
    if (attributes != null) {
      for (Entry<String, Boolean> a : attributes.entrySet()) {
        MemberValuePair mvp = ast.newMemberValuePair();
        mvp.setName(ast.newSimpleName(a.getKey()));
        mvp.setValue(ast.newBooleanLiteral(a.getValue()));
        normalAnnotation.values().add(mvp);
      }
    }
    return result;
  }

  /**
   * Add the given annotation if the method is non null
   */
  private void maybeAddAnnotations(AST ast, JUnitVisitor visitor, ASTRewrite rewriter,
      List<MethodDeclaration> methods, String annotation, String annotationToRemove) {
    maybeAddAnnotations(ast, visitor, rewriter, methods, annotation, annotationToRemove, null);
  }

  private void maybeAddAnnotations(AST ast, JUnitVisitor visitor,
      ASTRewrite rewriter, List<MethodDeclaration> methods, String annotation,
      String annotationToRemove, Map<String, Boolean> attributes) {
    for (MethodDeclaration method : methods) {
      maybeAddAnnotation(ast, visitor, rewriter, method, annotation, annotationToRemove,
          attributes);
    }
  }

  private void addAnnotation(AST ast, JUnitVisitor visitor, ASTRewrite rewriter,
      MethodDeclaration md, Annotation a, String annotationToRemove)
  {
    ListRewrite lr = rewriter.getListRewrite(md, MethodDeclaration.MODIFIERS2_PROPERTY);

    // Remove the annotation if applicable
    if (annotationToRemove != null) {
      List modifiers = md.modifiers();
      for (int k = 0; k < modifiers.size(); k++) {
        Object old = modifiers.get(k);
        if (old instanceof Annotation) {
          String oldAnnotation = old.toString();
          if (oldAnnotation.equals(annotationToRemove) || "@Override".equals(oldAnnotation)) {
            lr.remove((Annotation) old, null);
            break;
          }
        }
      }
    }

    // Add the annotation
    lr.insertFirst(a, null);
  }

  public String getName() {
    return "Convert to TestNG (Annotations)";
  }  
}
