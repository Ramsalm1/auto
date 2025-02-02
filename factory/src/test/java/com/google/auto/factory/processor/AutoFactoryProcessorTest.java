/*
 * Copyright 2013 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.auto.factory.processor;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.StandardSystemProperty.JAVA_SPECIFICATION_VERSION;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.TruthJUnit.assume;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.regex.Pattern.MULTILINE;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;
import javax.tools.JavaFileObject;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Functional tests for the {@link AutoFactoryProcessor}. */
@RunWith(JUnit4.class)
public class AutoFactoryProcessorTest {
  private final Compiler javac = Compiler.javac().withProcessors(new AutoFactoryProcessor());

  private static volatile boolean goldenFileFailures;

  private static final String GOLDEN_FILE_ROOT_ENV = "GOLDEN_FILE_ROOT";
  private static final String GOLDEN_FILE_ROOT = System.getenv(GOLDEN_FILE_ROOT_ENV);
  private static final Pattern CLASS_START =
      Pattern.compile("^(public )?(final )?class ", MULTILINE);

  @AfterClass
  public static void explainGoldenFileFailures() {
    if (goldenFileFailures) {
      System.err.println();
      System.err.println("Some golden-file tests failed.");
    }
  }

  /**
   * Runs a golden-file test, and optionally updates the golden file if the test fails.
   *
   * <p>If the golden file does not match current generated output, and the environment variable
   * {@value #GOLDEN_FILE_ROOT_ENV} is set to the root directory for resources, then the golden file
   * will be rewritten to match the generated output.
   *
   * @param inputResources resource names for the sources that the test will compile
   * @param expectedOutput map where each key is the name of an expected generated file, and each
   *     corresponding value is the name of the resource with the text that should have been
   *     generated
   */
  private void goldenTest(
      ImmutableList<String> inputResources, ImmutableMap<String, String> expectedOutput) {
    ImmutableList<JavaFileObject> javaFileObjects =
        inputResources.stream().map(JavaFileObjects::forResource).collect(toImmutableList());
    Compilation compilation = javac.compile(javaFileObjects);
    assertThat(compilation).succeededWithoutWarnings();
    expectedOutput.forEach(
        (className, expectedSourceResource) -> {
          try {
            assertThat(compilation)
                .generatedSourceFile(className)
                .hasSourceEquivalentTo(loadExpectedFile(expectedSourceResource));
          } catch (AssertionError e) {
            if (GOLDEN_FILE_ROOT == null) {
              goldenFileFailures = true;
              throw e;
            }
            try {
              updateGoldenFile(compilation, className, expectedSourceResource);
            } catch (IOException e2) {
              throw new UncheckedIOException(e2);
            }
          }
        });
  }

  private void updateGoldenFile(Compilation compilation, String className, String relativePath)
      throws IOException {
    Path goldenFileRootPath = Paths.get(GOLDEN_FILE_ROOT);
    Path goldenFilePath = goldenFileRootPath.resolve(relativePath);
    checkState(
        Files.isRegularFile(goldenFilePath) && Files.isWritable(goldenFilePath),
        "%s does not exist or can't be written",
        goldenFilePath);

    JavaFileObject newJavaFileObject =
        compilation
            .generatedSourceFile(className)
            .orElseThrow(() -> new IllegalStateException("No generated file for " + className));
    // We can't use Files.readString here because this test must run on Java 8.
    String oldContent = new String(Files.readAllBytes(goldenFilePath), UTF_8);
    String newContent =
        newJavaFileObject.getCharContent(/* ignoreEncodingErrors= */ false).toString();

    // We want to preserve the copyright notice and some minor Google-internal things that are
    // stripped from the open-source version. So keep text from the old golden file before the
    // class declaration.
    int oldPosition = indexOfClassStartIn(oldContent, "original " + relativePath);
    int newPosition = indexOfClassStartIn(newContent, "generated " + relativePath);
    String updatedContent =
        oldContent.substring(0, oldPosition) + newContent.substring(newPosition);
    // We can't use Files.writeString here because this test must run on Java 8.
    Files.write(goldenFilePath, updatedContent.getBytes(UTF_8));
    System.err.println("Updated " + goldenFilePath);
  }

  private int indexOfClassStartIn(String content, String where) {
    Matcher matcher = CLASS_START.matcher(content);
    boolean found = matcher.find();
    checkArgument(found, "Pattern /%s/ not found in %s:\n%s", CLASS_START, where, content);
    return matcher.start();
  }

  @Test
  public void simpleClass() {
    goldenTest(
        ImmutableList.of("good/SimpleClass.java"),
        ImmutableMap.of("tests.SimpleClassFactory", "expected/SimpleClassFactory.java"));
  }

  @Test
  public void simpleClassWithConstructorThrowsClause() {
    goldenTest(
        ImmutableList.of("good/SimpleClassThrows.java"),
        ImmutableMap.of(
            "tests.SimpleClassThrowsFactory", "expected/SimpleClassThrowsFactory.java"));
  }

  @Test
  public void nestedClasses() {
    goldenTest(
        ImmutableList.of("good/NestedClasses.java"),
        ImmutableMap.of(
            "tests.NestedClasses_SimpleNestedClassFactory",
            "expected/NestedClasses_SimpleNestedClassFactory.java",
            "tests.NestedClassCustomNamedFactory",
            "expected/NestedClassCustomNamedFactory.java"));
  }

  @Test
  public void simpleClassNonFinal() {
    goldenTest(
        ImmutableList.of("good/SimpleClassNonFinal.java"),
        ImmutableMap.of(
            "tests.SimpleClassNonFinalFactory", "expected/SimpleClassNonFinalFactory.java"));
  }

  @Test
  public void publicClass() {
    goldenTest(
        ImmutableList.of("good/PublicClass.java"),
        ImmutableMap.of("tests.PublicClassFactory", "expected/PublicClassFactory.java"));
  }

  @Test
  public void simpleClassCustomName() {
    goldenTest(
        ImmutableList.of("good/SimpleClassCustomName.java"),
        ImmutableMap.of("tests.CustomNamedFactory", "expected/CustomNamedFactory.java"));
  }

  @Test
  public void simpleClassMixedDeps() {
    goldenTest(
        ImmutableList.of("good/SimpleClassMixedDeps.java", "support/AQualifier.java"),
        ImmutableMap.of(
            "tests.SimpleClassMixedDepsFactory", "expected/SimpleClassMixedDepsFactory.java"));
  }

  @Test
  public void simpleClassPassedDeps() {
    goldenTest(
        ImmutableList.of("good/SimpleClassPassedDeps.java"),
        ImmutableMap.of(
            "tests.SimpleClassPassedDepsFactory", "expected/SimpleClassPassedDepsFactory.java"));
  }

  @Test
  public void simpleClassProvidedDeps() {
    goldenTest(
        ImmutableList.of(
            "good/SimpleClassProvidedDeps.java",
            "support/AQualifier.java",
            "support/BQualifier.java"),
        ImmutableMap.of(
            "tests.SimpleClassProvidedDepsFactory",
            "expected/SimpleClassProvidedDepsFactory.java"));
  }

  @Test
  public void simpleClassProvidedProviderDeps() {
    goldenTest(
        ImmutableList.of(
            "good/SimpleClassProvidedProviderDeps.java",
            "support/AQualifier.java",
            "support/BQualifier.java"),
        ImmutableMap.of(
            "tests.SimpleClassProvidedProviderDepsFactory",
            "expected/SimpleClassProvidedProviderDepsFactory.java"));
  }

  @Test
  public void constructorAnnotated() {
    goldenTest(
        ImmutableList.of("good/ConstructorAnnotated.java"),
        ImmutableMap.of(
            "tests.ConstructorAnnotatedFactory", "expected/ConstructorAnnotatedFactory.java"));
  }

  @Test
  public void constructorWithThrowsClauseAnnotated() {
    goldenTest(
        ImmutableList.of("good/ConstructorAnnotatedThrows.java"),
        ImmutableMap.of(
            "tests.ConstructorAnnotatedThrowsFactory",
            "expected/ConstructorAnnotatedThrowsFactory.java"));
  }

  @Test
  public void constructorAnnotatedNonFinal() {
    goldenTest(
        ImmutableList.of("good/ConstructorAnnotatedNonFinal.java"),
        ImmutableMap.of(
            "tests.ConstructorAnnotatedNonFinalFactory",
            "expected/ConstructorAnnotatedNonFinalFactory.java"));
  }

  @Test
  public void simpleClassImplementingMarker() {
    goldenTest(
        ImmutableList.of("good/SimpleClassImplementingMarker.java"),
        ImmutableMap.of(
            "tests.SimpleClassImplementingMarkerFactory",
            "expected/SimpleClassImplementingMarkerFactory.java"));
  }

  @Test
  public void simpleClassImplementingSimpleInterface() {
    goldenTest(
        ImmutableList.of("good/SimpleClassImplementingSimpleInterface.java"),
        ImmutableMap.of(
            "tests.SimpleClassImplementingSimpleInterfaceFactory",
            "expected/SimpleClassImplementingSimpleInterfaceFactory.java"));
  }

  @Test
  public void mixedDepsImplementingInterfaces() {
    goldenTest(
        ImmutableList.of("good/MixedDepsImplementingInterfaces.java"),
        ImmutableMap.of(
            "tests.MixedDepsImplementingInterfacesFactory",
            "expected/MixedDepsImplementingInterfacesFactory.java"));
  }

  @Test
  public void failsWithMixedFinals() {
    JavaFileObject file = JavaFileObjects.forResource("bad/MixedFinals.java");
    Compilation compilation = javac.compile(file);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            "Cannot mix allowSubclasses=true and allowSubclasses=false in one factory.")
        .inFile(file)
        .onLine(24);
    assertThat(compilation)
        .hadErrorContaining(
            "Cannot mix allowSubclasses=true and allowSubclasses=false in one factory.")
        .inFile(file)
        .onLine(27);
  }

  @Test
  public void providedButNoAutoFactory() {
    JavaFileObject file = JavaFileObjects.forResource("bad/ProvidedButNoAutoFactory.java");
    Compilation compilation = javac.compile(file);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            "@Provided may only be applied to constructors requesting an auto-factory")
        .inFile(file)
        .onLineContaining("@Provided");
  }

  @Test
  public void providedOnMethodParameter() {
    JavaFileObject file = JavaFileObjects.forResource("bad/ProvidedOnMethodParameter.java");
    Compilation compilation = javac.compile(file);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining("@Provided may only be applied to constructor parameters")
        .inFile(file)
        .onLineContaining("@Provided");
  }

  @Test
  public void invalidCustomName() {
    JavaFileObject file = JavaFileObjects.forResource("bad/InvalidCustomName.java");
    Compilation compilation = javac.compile(file);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining("\"SillyFactory!\" is not a valid Java identifier")
        .inFile(file)
        .onLineContaining("SillyFactory!");
  }

  @Test
  public void factoryExtendingAbstractClass() {
    goldenTest(
        ImmutableList.of("good/FactoryExtendingAbstractClass.java"),
        ImmutableMap.of(
            "tests.FactoryExtendingAbstractClassFactory",
            "expected/FactoryExtendingAbstractClassFactory.java"));
  }

  @Test
  public void factoryWithConstructorThrowsClauseExtendingAbstractClass() {
    goldenTest(
        ImmutableList.of("good/FactoryExtendingAbstractClassThrows.java"),
        ImmutableMap.of(
            "tests.FactoryExtendingAbstractClassThrowsFactory",
            "expected/FactoryExtendingAbstractClassThrowsFactory.java"));
  }

  @Test
  public void factoryExtendingAbstractClass_withConstructorParams() {
    JavaFileObject file =
        JavaFileObjects.forResource("bad/FactoryExtendingAbstractClassWithConstructorParams.java");
    Compilation compilation = javac.compile(file);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            "tests.FactoryExtendingAbstractClassWithConstructorParams.AbstractFactory is not a"
                + " valid supertype for a factory. Factory supertypes must have a no-arg"
                + " constructor.")
        .inFile(file)
        .onLineContaining("@AutoFactory");
  }

  @Test
  public void factoryExtendingAbstractClass_multipleConstructors() {
    goldenTest(
        ImmutableList.of("good/FactoryExtendingAbstractClassWithMultipleConstructors.java"),
        ImmutableMap.of());
  }

  @Test
  public void factoryExtendingInterface() {
    JavaFileObject file = JavaFileObjects.forResource("bad/InterfaceSupertype.java");
    Compilation compilation = javac.compile(file);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            "java.lang.Runnable is not a valid supertype for a factory. Supertypes must be"
                + " non-final classes.")
        .inFile(file)
        .onLineContaining("@AutoFactory");
  }

  @Test
  public void factoryExtendingEnum() {
    JavaFileObject file = JavaFileObjects.forResource("bad/EnumSupertype.java");
    Compilation compilation = javac.compile(file);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            "java.util.concurrent.TimeUnit is not a valid supertype for a factory. Supertypes must"
                + " be non-final classes.")
        .inFile(file)
        .onLineContaining("@AutoFactory");
  }

  @Test
  public void factoryExtendingFinalClass() {
    JavaFileObject file = JavaFileObjects.forResource("bad/FinalSupertype.java");
    Compilation compilation = javac.compile(file);
    assertThat(compilation).failed();
    assertThat(compilation)
        .hadErrorContaining(
            "java.lang.Boolean is not a valid supertype for a factory. Supertypes must be"
                + " non-final classes.")
        .inFile(file)
        .onLineContaining("@AutoFactory");
  }

  @Test
  public void factoryImplementingGenericInterfaceExtension() {
    goldenTest(
        ImmutableList.of("good/FactoryImplementingGenericInterfaceExtension.java"),
        ImmutableMap.of(
            "tests.FactoryImplementingGenericInterfaceExtensionFactory",
            "expected/FactoryImplementingGenericInterfaceExtensionFactory.java"));
  }

  @Test
  public void multipleFactoriesImpementingInterface() {
    goldenTest(
        ImmutableList.of("good/MultipleFactoriesImplementingInterface.java"),
        ImmutableMap.of(
            "tests.MultipleFactoriesImplementingInterface_ClassAFactory",
                "expected/MultipleFactoriesImplementingInterface_ClassAFactory.java",
            "tests.MultipleFactoriesImplementingInterface_ClassBFactory",
                "expected/MultipleFactoriesImplementingInterface_ClassBFactory.java"));
  }

  @Test
  public void classUsingQualifierWithArgs() {
    goldenTest(
        ImmutableList.of("good/ClassUsingQualifierWithArgs.java", "support/QualifierWithArgs.java"),
        ImmutableMap.of(
            "tests.ClassUsingQualifierWithArgsFactory",
            "expected/ClassUsingQualifierWithArgsFactory.java"));
  }

  @Test
  public void factoryImplementingInterfaceWhichRedeclaresCreateMethods() {
    goldenTest(
        ImmutableList.of("good/FactoryImplementingCreateMethod.java"),
        ImmutableMap.of(
            "tests.FactoryImplementingCreateMethod_ConcreteClassFactory",
            "expected/FactoryImplementingCreateMethod_ConcreteClassFactory.java"));
  }

  @Test
  public void nullableParams() {
    goldenTest(
        ImmutableList.of(
            "good/SimpleClassNullableParameters.java",
            "support/AQualifier.java",
            "support/BQualifier.java"),
        ImmutableMap.of(
            "tests.SimpleClassNullableParametersFactory",
            "expected/SimpleClassNullableParametersFactory.java"));
  }

  @Test
  public void customNullableType() {
    goldenTest(
        ImmutableList.of("good/CustomNullable.java"),
        ImmutableMap.of("tests.CustomNullableFactory", "expected/CustomNullableFactory.java"));
  }

  @Test
  public void checkerFrameworkNullableType() {
    // TYPE_USE annotations are pretty much unusable with annotation processors on Java 8 because
    // of bugs that mean they only appear in the javax.lang.model API when the compiler feels like
    // it. Checking for a java.specification.version that does not start with "1." eliminates 8 and
    // any earlier version.
    assume().that(JAVA_SPECIFICATION_VERSION.value()).doesNotMatch("1\\..*");
    goldenTest(
        ImmutableList.of("good/CheckerFrameworkNullable.java"),
        ImmutableMap.of(
            "tests.CheckerFrameworkNullableFactory",
            "expected/CheckerFrameworkNullableFactory.java"));
  }

  @Test
  public void multipleProvidedParamsWithSameKey() {
    goldenTest(
        ImmutableList.of("good/MultipleProvidedParamsSameKey.java"),
        ImmutableMap.of(
            "tests.MultipleProvidedParamsSameKeyFactory",
            "expected/MultipleProvidedParamsSameKeyFactory.java"));
  }

  @Test
  public void providerArgumentToCreateMethod() {
    goldenTest(
        ImmutableList.of("good/ProviderArgumentToCreateMethod.java"),
        ImmutableMap.of(
            "tests.ProviderArgumentToCreateMethodFactory",
            "expected/ProviderArgumentToCreateMethodFactory.java"));
  }

  @Test
  public void multipleFactoriesConflictingParameterNames() {
    goldenTest(
        ImmutableList.of(
            "good/MultipleFactoriesConflictingParameterNames.java", "support/AQualifier.java"),
        ImmutableMap.of(
            "tests.MultipleFactoriesConflictingParameterNamesFactory",
            "expected/MultipleFactoriesConflictingParameterNamesFactory.java"));
  }

  @Test
  public void factoryVarargs() {
    goldenTest(
        ImmutableList.of("good/SimpleClassVarargs.java"),
        ImmutableMap.of(
            "tests.SimpleClassVarargsFactory", "expected/SimpleClassVarargsFactory.java"));
  }

  @Test
  public void onlyPrimitives() {
    goldenTest(
        ImmutableList.of("good/OnlyPrimitives.java"),
        ImmutableMap.of("tests.OnlyPrimitivesFactory", "expected/OnlyPrimitivesFactory.java"));
  }

  @Test
  public void defaultPackage() {
    goldenTest(
        ImmutableList.of("good/DefaultPackage.java"),
        ImmutableMap.of("DefaultPackageFactory", "expected/DefaultPackageFactory.java"));
  }

  @Test
  public void generics() {
    goldenTest(
        ImmutableList.of("good/Generics.java"),
        ImmutableMap.of(
            "tests.Generics_FooImplFactory",
            "expected/Generics_FooImplFactory.java",
            "tests.Generics_ExplicitFooImplFactory",
            "expected/Generics_ExplicitFooImplFactory.java",
            "tests.Generics_FooImplWithClassFactory",
            "expected/Generics_FooImplWithClassFactory.java"));
  }

  @Test
  public void parameterAnnotations() {
    goldenTest(
        ImmutableList.of("good/ParameterAnnotations.java"),
        ImmutableMap.of(
            "tests.ParameterAnnotationsFactory", "expected/ParameterAnnotationsFactory.java"));
  }

  private JavaFileObject loadExpectedFile(String resourceName) {
    if (isJavaxAnnotationProcessingGeneratedAvailable()) {
      return JavaFileObjects.forResource(resourceName);
    }
    try {
      List<String> sourceLines = Resources.readLines(Resources.getResource(resourceName), UTF_8);
      replaceGeneratedImport(sourceLines);
      return JavaFileObjects.forSourceLines(
          resourceName.replace('/', '.').replace(".java", ""), sourceLines);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private boolean isJavaxAnnotationProcessingGeneratedAvailable() {
    return SourceVersion.latestSupported().compareTo(SourceVersion.RELEASE_8) > 0;
  }

  private static void replaceGeneratedImport(List<String> sourceLines) {
    int i = 0;
    int firstImport = Integer.MAX_VALUE;
    int lastImport = -1;
    for (String line : sourceLines) {
      if (line.startsWith("import ") && !line.startsWith("import static ")) {
        firstImport = min(firstImport, i);
        lastImport = max(lastImport, i);
      }
      i++;
    }
    if (lastImport >= 0) {
      List<String> importLines = sourceLines.subList(firstImport, lastImport + 1);
      importLines.replaceAll(
          line ->
              line.startsWith("import javax.annotation.processing.Generated;")
                  ? "import javax.annotation.Generated;"
                  : line);
      Collections.sort(importLines);
    }
  }
}
