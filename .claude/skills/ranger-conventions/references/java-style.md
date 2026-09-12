<!--
 - Licensed to the Apache Software Foundation (ASF) under one or more
 - contributor license agreements.  See the NOTICE file distributed with
 - this work for additional information regarding copyright ownership.
 - The ASF licenses this file to You under the Apache License, Version 2.0
 - (the "License"); you may not use this file except in compliance with
 - the License.  You may obtain a copy of the License at
 -
 -   http://www.apache.org/licenses/LICENSE-2.0
 -
 - Unless required by applicable law or agreed to in writing, software
 - distributed under the License is distributed on an "AS IS" BASIS,
 - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 - See the License for the specific language governing permissions and
 - limitations under the License.
 -->

# Java style: what the tools enforce

`maven-checkstyle-plugin` 3.1.0 / checkstyle 8.29 (`dev-support/checkstyle.xml`, suppressions `dev-support/checkstyle-suppressions.xml`), `verify` phase,
`failOnViolation=true`, main and test sources. PMD (`dev-support/ranger-pmd-ruleset.xml`, `failOnViolation=true`, main sources only). SpotBugs is advisory
(`spotbugs.failOnViolation=false`, priority 1 only). Reference: `mkdocs/docs/project/java-code-style.md` and `.cursor/rules/ranger-checkstyle.mdc`.

## File-level

- `FileTabCharacter`, `NewlineAtEndOfFile` (lf), no `\r`, no trailing whitespace, no `\n\n\n`, no blank line before EOF, no blank line after `{` or before `}`,
  no whitespace before `)`, lambda `{` on the `->` line, `new Type[] {` spacing.
- Static imports: may not statically import `of`, `copyOf`, `valueOf`, `builder`, or `java.util.Optional.*`; only `java.lang.String.format` may be imported as `format`.
  Must be statically imported: `Objects.requireNonNull`, `Math.toIntExact`, `ImmutableList/Set/Map.toImmutable*`, Guava `MoreObjects`/`Preconditions`/`Verify` members.

## Tree-level

- Imports: `AvoidStarImport`, `RedundantImport`, `UnusedImports`, `ImportOrder groups="*,javax,java" separated option=bottom sortStaticImportsAlphabetically`.
- Braces: `NeedBraces`, `LeftCurly eol`, `RightCurly alone` for `else`. `EmptyBlock` (text) for control statements, `EmptyStatement`.
- Whitespace: `WhitespaceAround`, `WhitespaceAfter`, `NoWhitespaceAfter`/`Before`, `GenericWhitespace`, `ParenPad`, `MethodParamPad`, `TypecastParenPad`.
  `SingleSpaceSeparator` is off, which is what permits column alignment.
- `Indentation` basicOffset 4, `lineWrappingIndentation` 8, `throwsIndent` 8.
- `EmptyLineSeparator` (fields may be adjacent), `ModifierOrder`, `RedundantModifier`, `OneStatementPerLine`, `MultipleVariableDeclarations`, `StringLiteralEquality`,
  `DefaultComesLast`, `ArrayTypeStyle`, `UpperEll`, `InnerAssignment`, `MutableException`, `EqualsHashCode`, `HideUtilityClassConstructor`, `ExplicitInitialization`,
  `OneTopLevelClass`, `PackageDeclaration`, `AnnotationUseStyle`.
- Naming: `TypeName`, `MethodName` (`^[a-z][a-zA-Z0-9_]*$`), `MemberName`, `ParameterName`, `LocalVariableName`, `StaticVariableName`, `PackageName`
  (`^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*$`), type parameters `^[A-Z][A-Za-z0-9]*$`.
- `IllegalToken LITERAL_ASSERT`: no `assert`.
- Not enforced: line length (IntelliJ margin is 512), Javadoc, final locals, magic numbers.

## Suppressions

`dev-support/checkstyle-suppressions.xml` lists per-file exceptions, mainly `TypeName` for `security-admin` patch classes `Patch*_J100NN.java`.
**Any new `Patch*_J<n>` class needs a matching entry.** Also `StaticVariableName` (`BaseDao`, `ServiceDBStore`, `TagDBStore`, ...) and
`HideUtilityClassConstructor` (`ContextUtil`, `MapUtil`, `RangerCommonEnums`, `TimedEventUtil`).

## PMD

Categories `codestyle`, `bestpractices`, `multithreading` with long exclusion lists (`OnlyOneReturn`, `ShortVariable`, `GuardLogStatement`, `SystemPrintln`,
`MissingOverride`, `UseConcurrentHashMap`, ...). If PMD flags something, check the ruleset before working around it.

## IntelliJ scheme (`dev-support/RangerCodeScheme-IntelliJ.xml`)

Import layout matching checkstyle, `CLASS_COUNT_TO_USE_IMPORT_ON_DEMAND=9999`, `RIGHT_MARGIN=512`,
`ALIGN_GROUP_FIELD_DECLARATIONS`, `ALIGN_CONSECUTIVE_VARIABLE_DECLARATIONS`, `ALIGN_CONSECUTIVE_ASSIGNMENTS`, `ALIGN_SUBSEQUENT_SIMPLE_METHODS` all true,
`ELSE_ON_NEW_LINE=false`, `IF/FOR/WHILE_BRACE_FORCE` always, `KEEP_BLANK_LINES_IN_CODE=1`, `SPACE_WITHIN_ARRAY_INITIALIZER_BRACES=true`.
Member arrangement: static final (public > protected > package > private), static, final instance, instance, constructors, static methods, methods by visibility, nested types.

Example of the aligned style:

```java
private static final String RANGER_PLUGIN_TYPE                     = "kafka";
private static final String RANGER_KAFKA_AUTHORIZER_IMPL_CLASSNAME = "org.apache.ranger.authorization.kafka.authorizer.RangerKafkaAuthorizer";

private final RangerAuthzConfig              config;
private final Map<String, RangerAuthzPlugin> plugins = new ConcurrentHashMap<>();

this.config  = new RangerAuthzConfig(properties);
this.appType = config.getAppType();
```

## Single return

```java
private String resolveAuthenticatedLoginId() {
    String loginId = bizUtil.getCurrentUserLoginId();

    if (loginId == null) {
        Object authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof RangerAuthenticationToken) {
            loginId = ((RangerAuthenticationToken) authentication).getName();
        }
    }

    return loginId;
}
```

Throwing early (`throw restErrorUtil.createRESTException(...)`) is fine; multiple `return` statements are not.
