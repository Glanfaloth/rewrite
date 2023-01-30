/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java;

import org.openrewrite.Cursor;
import org.openrewrite.Tree;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.internal.template.JavaTemplateJavaExtension;
import org.openrewrite.java.internal.template.JavaTemplateParser;
import org.openrewrite.java.internal.template.Substitutions;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaCoordinates;
import org.openrewrite.java.tree.Space.Location;
import org.openrewrite.template.SourceTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class JavaTemplate implements SourceTemplate<J, JavaCoordinates> {
    private final Supplier<Cursor> parentScopeGetter;
    private final String code;
    private final int parameterCount;
    private final Consumer<String> onAfterVariableSubstitution;
    private final JavaTemplateParser templateParser;

    private JavaTemplate(Supplier<Cursor> parentScopeGetter, Supplier<JavaParser> parser, String code, Set<String> imports,
                         Consumer<String> onAfterVariableSubstitution, Consumer<String> onBeforeParseTemplate) {
        this.parentScopeGetter = parentScopeGetter;
        this.code = code;
        this.onAfterVariableSubstitution = onAfterVariableSubstitution;
        this.parameterCount = StringUtils.countOccurrences(code, "#{");
        this.templateParser = new JavaTemplateParser(parser, onAfterVariableSubstitution, onBeforeParseTemplate, imports);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <J2 extends J> J2 withTemplate(Tree changing, JavaCoordinates coordinates, Object[] parameters) {
        if (parameters.length != parameterCount) {
            throw new IllegalArgumentException("This template requires " + parameterCount + " parameters.");
        }

        Substitutions substitutions = new Substitutions(code, parameters);
        String substitutedTemplate = substitutions.substitute();
        onAfterVariableSubstitution.accept(substitutedTemplate);

        Tree insertionPoint = coordinates.getTree();
        Location loc = coordinates.getSpaceLocation();
        JavaCoordinates.Mode mode = coordinates.getMode();

        AtomicReference<Cursor> parentCursorRef = new AtomicReference<>();

        // Find the parent cursor of the CHANGING element, which may not be the same as the cursor of
        // the method using the template. For example, using a template on a class declaration body
        // inside visitClassDeclaration. The body is the changing element, but the current cursor
        // is at the class declaration.
        //
        //      J visitClassDeclaration(J.ClassDeclaration c, Integer p) {
        //            c.getBody().withTemplate(template, c.getBody().coordinates.lastStatement());
        //      }
        Cursor parentScope = parentScopeGetter.get();
        if (!(parentScope.getValue() instanceof J)) {
            // Handle the provided parent cursor pointing to a JRightPadded or similar
            parentScope = parentScope.getParentTreeCursor();
        }
        new JavaIsoVisitor<Integer>() {
            @Nullable
            @Override
            public J visit(@Nullable Tree tree, Integer integer) {
                if (tree != null && tree.isScope(changing)) {
                    // Currently getCursor still points to the parent, because super.visit() has the logic to update it
                    Cursor cursor = getCursor();
                    if (!(cursor.getValue() instanceof J)) {
                        cursor = cursor.getParentTreeCursor();
                    }
                    parentCursorRef.set(cursor);
                    return (J) tree;
                }
                return super.visit(tree, integer);
            }
        }.visit(parentScope.getValue(), 0, parentScope.getParentOrThrow());

        Cursor parentCursor = parentCursorRef.get();

        //noinspection ConstantConditions
        return (J2) new JavaTemplateJavaExtension(templateParser, substitutions, substitutedTemplate, coordinates, parentCursorRef, parentScope)
                .getMixin()
                .visit(changing, 0, parentCursor);
    }

    public static Builder builder(Supplier<Cursor> parentScope, String code) {
        return new Builder(parentScope, code);
    }

    @SuppressWarnings("unused")
    public static class Builder {
        private final Supplier<Cursor> parentScope;
        private final String code;
        private final Set<String> imports = new HashSet<>();

        private Supplier<JavaParser> javaParser = () -> JavaParser.fromJavaVersion().build();

        private Consumer<String> onAfterVariableSubstitution = s -> {
        };
        private Consumer<String> onBeforeParseTemplate = s -> {
        };

        Builder(Supplier<Cursor> parentScope, String code) {
            this.parentScope = parentScope;
            this.code = code.trim();
        }

        public Builder imports(String... fullyQualifiedTypeNames) {
            for (String typeName : fullyQualifiedTypeNames) {
                if (shouldAddImport(typeName)) {
                    this.imports.add("import " + typeName + ";\n");
                }
            }
            return this;
        }

        public Builder staticImports(String... fullyQualifiedMemberTypeNames) {
            for (String typeName : fullyQualifiedMemberTypeNames) {
                if (shouldAddImport(typeName)) {
                    this.imports.add("import static " + typeName + ";\n");
                }
            }
            return this;
        }

        private boolean shouldAddImport(String typeName) {
            if (StringUtils.isBlank(typeName)) {
                return false;
            } else if (typeName.startsWith("import ") || typeName.startsWith("static ")) {
                throw new IllegalArgumentException("Imports are expressed as fully-qualified names and should not include an \"import \" or \"static \" prefix");
            } else if (typeName.endsWith(";") || typeName.endsWith("\n")) {
                throw new IllegalArgumentException("Imports are expressed as fully-qualified names and should not include a suffixed terminator");
            }
            return true;
        }

        public Builder javaParser(Supplier<JavaParser> javaParser) {
            this.javaParser = javaParser;
            return this;
        }

        public Builder doAfterVariableSubstitution(Consumer<String> afterVariableSubstitution) {
            this.onAfterVariableSubstitution = afterVariableSubstitution;
            return this;
        }

        public Builder doBeforeParseTemplate(Consumer<String> beforeParseTemplate) {
            this.onBeforeParseTemplate = beforeParseTemplate;
            return this;
        }

        public JavaTemplate build() {
            return new JavaTemplate(parentScope, javaParser, code, imports,
                    onAfterVariableSubstitution, onBeforeParseTemplate);
        }
    }
}
