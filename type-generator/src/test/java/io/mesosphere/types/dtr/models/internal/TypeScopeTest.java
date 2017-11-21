package io.mesosphere.types.dtr.models.internal;

import io.mesosphere.types.dtr.models.internal.scopes.FileScope;
import io.mesosphere.types.dtr.models.internal.scopes.GlobalScope;
import io.mesosphere.types.dtr.models.internal.scopes.TypeDefinitionScope;
import io.mesosphere.types.dtr.models.internal.scopes.TypeInlineScope;
import io.mesosphere.types.dtr.models.internal.types.ScalarType;
import io.mesosphere.types.dtr.models.internal.values.AnyValue;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

public class TypeScopeTest {

    /**
     * A few re-usable type names
     */
    private static final Type TypeA = new ScalarType((Type)null, "TypeA", new AnyValue());
    private static final Type TypeB = new ScalarType((Type)null, "TypeB", new AnyValue());
    private static final Type TypeC = new ScalarType((Type)null, "TypeC", new AnyValue());

    /**
     * Test the basic define/resolve process
     * @throws Exception
     */
    @Test
    public void testResolution() throws Exception {
        TypeScope scope = new GlobalScope();
        CompletableFuture<Type> resolution;

        // Existing type should be returned
        scope.define("TypeA", TypeA);
        resolution = scope.resolve("TypeA");
        assertEquals(true, resolution.isDone());
        assertEquals(TypeA, resolution.join());

        // Missing type should create a future in an incomplete state
        resolution = scope.resolve("TypeB");
        assertEquals(false, resolution.isDone());

        // .. that is resolved automatically when defined
        scope.define("TypeB", TypeB);
        assertEquals(true, resolution.isDone());
        assertEquals(TypeB, resolution.join());
    }

    /**
     * Child scopes should walk up the parent scopes until they find
     * the defined type
     * @throws Exception
     */
    @Test
    public void testParentResolution() throws Exception {
        TypeScope scope = new GlobalScope();
        TypeScope childScope = new FileScope(scope, "test.file");
        CompletableFuture<Type> resolution;

        // Type resolution should walk up to parent
        scope.define("TypeA", TypeA);
        resolution = childScope.resolve("TypeA");
        assertEquals(true, resolution.isDone());
        assertEquals(TypeA, resolution.join());
    }

    /**
     * Child scopes should be isolated
     * @throws Exception
     */
    @Test
    public void testScopeIsolation() throws Exception {
        TypeScope scope = new GlobalScope();
        TypeScope childScope1 = new FileScope(scope, "test1.file");
        TypeScope childScope2 = new FileScope(scope, "test2.file");
        CompletableFuture<Type> resolution;

        // Define something in parent and in each child
        scope.define("TypeA", TypeA);
        childScope1.define("TypeB", TypeB);
        childScope2.define("TypeC", TypeC);

        // Both childs should resolve TypeA
        resolution = childScope1.resolve("TypeA");
        assertEquals(true, resolution.isDone());
        assertEquals(TypeA, resolution.join());
        resolution = childScope1.resolve("TypeA");
        assertEquals(true, resolution.isDone());
        assertEquals(TypeA, resolution.join());

        // Only childScope1 should resolve TypeB
        resolution = childScope1.resolve("TypeB");
        assertEquals(true, resolution.isDone());
        assertEquals(TypeB, resolution.join());
        resolution = childScope2.resolve("TypeB");
        assertEquals(false, resolution.isDone());

        // Only childScope2 should resolve TypeC
        resolution = childScope1.resolve("TypeC");
        assertEquals(false, resolution.isDone());
        resolution = childScope2.resolve("TypeC");
        assertEquals(true, resolution.isDone());
        assertEquals(TypeC, resolution.join());
    }

    /**
     * When a child scope tries to resolve something that does not know it
     * should bubble up the request to parent(s). However if the scope is defined
     * in an inner scope the parent resolutions should be ignored.
     * @throws Exception
     */
    @Test
    public void testScopeResolutionOrder() throws Exception {
        TypeScope scope = new GlobalScope();
        TypeScope childScope = new FileScope(scope, "test1.file");
        CompletableFuture<Type> resolution;

        // Block on resolving TypeA on the child
        resolution = childScope.resolve("TypeA");
        assertEquals(false, resolution.isDone());

        // Defining something on childScope should resolve TypeA
        childScope.define("TypeA", TypeA);
        assertEquals(true, resolution.isDone());
        assertEquals(TypeA, resolution.join());

        // Defining something on the globalScope should be ignored
        scope.define("TypeA", TypeB);
        assertEquals(true, resolution.isDone());
        assertEquals(TypeA, resolution.join());

    }


    /**
     * Local types take preceedence on parent types
     * @throws Exception
     */
    @Test
    public void testParentResolutionOrder() throws Exception {
        TypeScope scope = new GlobalScope();
        TypeScope childScope = new FileScope(scope, "test.file");
        CompletableFuture<Type> resolution;

        // Child scopes are preferred in case of name clash
        scope.define("TypeA", TypeA);
        childScope.define("TypeA", TypeB);
        resolution = childScope.resolve("TypeA");
        assertEquals(true, resolution.isDone());
        assertEquals(TypeB, resolution.join());
    }

    /**
     * Other imports can be brought from other scopes using named imports
     * @throws Exception
     */
    @Test
    public void testNamedImports() throws Exception {
        TypeScope scope = new GlobalScope();
        TypeScope aScope = new GlobalScope();
        TypeScope bScope = new GlobalScope();
        CompletableFuture<Type> resolutionA, resolutionB;

        // Define some types on the two scopes
        aScope.define("TypeB", TypeB);
        bScope.define("TypeB", TypeB);

        // Without any import the type should be missing
        resolutionA = scope.resolve("a.TypeB");
        resolutionB = scope.resolve("b.TypeB");
        assertEquals(false, resolutionA.isDone());
        assertEquals(false, resolutionB.isDone());

        // Importing "a" will define a.*
        scope.importFrom(aScope, "a");
        assertEquals(true, resolutionA.isDone());
        assertEquals(false, resolutionB.isDone());
        assertEquals(TypeB, resolutionA.join());

        // Importing "b" will also define b.*
        scope.importFrom(bScope, "b");
        assertEquals(true, resolutionA.isDone());
        assertEquals(true, resolutionB.isDone());
        assertEquals(TypeB, resolutionA.join());
        assertEquals(TypeB, resolutionB.join());

        // Imports could also be lazy-resolved (ex. after we have
        // already imported the scope)
        resolutionA = scope.resolve("a.TypeA");
        assertEquals(false, resolutionA.isDone());
        aScope.define("TypeA", TypeA);
        assertEquals(true, resolutionA.isDone());
        assertEquals(TypeA, resolutionA.get());

        resolutionB = scope.resolve("b.TypeC");
        assertEquals(false, resolutionB.isDone());
        bScope.define("TypeC", TypeC);
        assertEquals(true, resolutionB.isDone());
        assertEquals(TypeC, resolutionB.get());
    }

    /**
     * Checks if an import on the parent scope satisfies pending imports on the child scopes
     */
    @Test
    public void testNamedImportsOnChildren() throws ExecutionException, InterruptedException {
        CompletableFuture<Type> resolutionA, resolutionB;

        // Create a chained scope scope -> aScope -> bScope
        TypeScope scope = new GlobalScope();
        TypeScope aScope = new TypeDefinitionScope(scope, "SomeType");
        TypeScope bScope = new TypeInlineScope(aScope, "inline_type");

        // Define "TypeA" on a new "import scope"
        TypeScope importScope = new GlobalScope();
        importScope.define("TypeA", TypeA);

        // Nothing should be defined
        resolutionA = bScope.resolve("a.TypeA");
        resolutionB = bScope.resolve("a.TypeB");
        assertEquals(false, resolutionA.isDone());
        assertEquals(false, resolutionB.isDone());

        // When we import scope, "a.TypeA" should be made available in the deepest scope
        scope.importFrom(importScope, "a");
        assertEquals(true, resolutionA.isDone());
        assertEquals(TypeA, resolutionA.get());
        assertEquals(false, resolutionB.isDone());

        // When something is changed in the import, the resolution should also arrive deep down
        importScope.define("TypeB", TypeB);
        assertEquals(true, resolutionA.isDone());
        assertEquals(TypeA, resolutionA.get());
        assertEquals(true, resolutionB.isDone());
        assertEquals(TypeB, resolutionB.get());
    }

}