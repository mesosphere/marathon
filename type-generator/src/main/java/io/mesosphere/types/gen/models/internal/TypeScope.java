package io.mesosphere.types.gen.models.internal;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A Type scope holds the type definitions. It operates in the same context
 * as all of the programming languages: The types can be defined in the global
 * scope, or they could be defined within a smaller scope (ex. function block).
 * <p>
 * This approach is particularly useful in RAML, where type definitions can be
 * in-lined using string expressions.
 */
public abstract class TypeScope {

    /**
     * The separator to use when resolving named imports.
     * For instance:
     * <p>
     * uses:
     * refName: someFile.raml
     * types:
     * MyType:
     * type: refName.SomeImport # << The separator is the "."
     */
    private final String NAMESPACE_SEPARATOR = ".";

    /**
     * Auto-generated constant from the NAMESPACE_SEPARATOR constant
     */
    private final Pattern NAMESPACE_SEPARATOR_REGEX = Pattern.compile(NAMESPACE_SEPARATOR, Pattern.LITERAL);

    /**
     * The item ID is acumulative
     */
    static private int id_counter = 0;

    /**
     * The name of this scope
     */
    public String name;

    /**
     * The parent scope
     */
    private TypeScope parent;

    /**
     * The child scopes
     */
    private List<TypeScope> childScopes;

    /**
     * Defined types in this
     */
    private Map<String, Type> definitions;

    /**
     * Named references to other scopes
     */
    private Map<String, TypeScope> namespaces;

    /**
     * Incomplete resolutions that are waiting for the definition to appear
     */
    private Map<String, List<CompletableFuture<Type>>> incompleteResolutions;

    /**
     * Relations between types
     */
    public Map<String, List<TypeScopeRelation>> relations;

    /**
     * Instantiate a new type scope with the given name
     *
     * @param parent The parent scope this scope belongs to
     * @param name   The name of the new scope
     */
    public TypeScope(TypeScope parent, String name) {
        this.name = name;
        this.parent = parent;
        this.childScopes = new ArrayList<>();
        this.definitions = new HashMap<>();
        this.namespaces = new HashMap<>();
        this.incompleteResolutions = new HashMap<>();
        this.relations = new HashMap<>();

        // Register child on parent scope
        if (parent != null) {
            parent.childScopes.add(this);
        }
    }

    /**
     * Override finalize method in order to unregister ourselves from the parent scope
     *
     * @throws Throwable
     */
    @Override
    protected void finalize() throws Throwable {

        // Unregister myself from the parent scope
        if (parent != null) {
            parent.childScopes.remove(this);
        }

        super.finalize();
    }

    /**
     * Returns the namespace of the provided type expression or `null` if it's not namespaced
     * @param name The type expression to check
     * @return Returns the namespace and type name expression as an array of 2 values
     */
    public String[] splitNamespace(String name) {
        if (!NAMESPACE_SEPARATOR_REGEX.matcher(name).find()) {
            return null;
        }

        return NAMESPACE_SEPARATOR_REGEX.split(name, 2);
    }

    /**
     * Checks if the specified type is defined
     * @param name The name of the type to check
     * @return Returns `true` if this type is defined
     */
    public boolean isDefined(String name) {

        // If the expression is namespaced, resolve through the namespace
        String[] ns = splitNamespace(name);
        if (ns != null) {
            return namespaces.containsKey(ns[0]) && this.namespaces.get(ns[0]).isDefined(ns[1]);
        }

        // Otherwise check our local scope
        return definitions.containsKey(name);
    }

    /**
     * Resolve a type by it's name
     * <p>
     * This function also resolves types in imported namespaces.
     *
     * @param name The name of the type to resolve
     * @return Returns a future that will be completed when the type is resolved
     */
    public CompletableFuture<Type> resolve(String name) {

        // If the expression is namespaced, resolve through the namespace
        String[] ns = splitNamespace(name);
        if (ns != null) {
            // If the namespace exists, forward the resolution to the namespace
            if (namespaces.containsKey(ns[0])) {
                return namespaces.get(ns[0]).resolve(ns[1]);
            }
        }

        // If the expression is not namespaced, resolve locally
        else {
            if (definitions.containsKey(name)) {
                return CompletableFuture.completedFuture(definitions.get(name));
            }
        }

        // Create a future that is going to be resolved when the type is resolved
        CompletableFuture<Type> future = new CompletableFuture<>();

        // Keep track of this future in the incomplete resolutions
        if (!incompleteResolutions.containsKey(name)) {
            incompleteResolutions.put(name, new ArrayList<>());
        }
        incompleteResolutions.get(name).add(future);

        // If we don't have a parent, we cannot forward this request anywhere,
        // so just return the future and wait for it to be resolved
        if (parent == null) return future;

        // Otherwise try to resolve this from parent
        parent.resolve(name).whenComplete((type, throwable) -> {
            if (throwable != null) {
                future.completeExceptionally(throwable);
                return;
            }

            // Trigger all resolutions pending
            this.satisfyDefinition(name, type);
        });

        // Return future
        return future;
    }

    /**
     * Satisfy all futures waiting for a resolution for the specified type
     * @param name The name of the type
     * @param type The type reference resolved
     */
    private void satisfyDefinition(String name, Type type) {
        // Satisfy pending futures
        if (incompleteResolutions.containsKey(name)) {
            for (CompletableFuture<Type> t: incompleteResolutions.get(name)) {
                t.complete(type);
            }
            incompleteResolutions.remove(name);
        }
    }

    /**
     * Define a new type
     * <p>
     * Note that every type *MUST* have a name. Auto-generated types will also
     * have a name. Even if that's just a random blob of characters.
     *
     * @param name The name of the type
     * @param type The type reference to store
     */
    public Type define(String name, Type type) {
        // Define type
        this.definitions.put(name, type);
        // Satisfy definitions
        this.satisfyDefinition(name, type);
        // Return type to allow chaining
        return type;
    }

    /**
     * Define a type with the name as the current scope
     * @param type The type to define
     * @return
     */
    public Type defineDefault(Type type) {
        if (parent == null) {
            return type;
        } else {
            return parent.define(this.name, type);
        }
    }

    /**
     * Establish a union relation between the given parent type and member type
     * @param superType The union type
     * @param memberType The member type
     */
    public void relateUnion(Type superType, Type memberType) {
        // Establish relation to the top of the chain
        if (parent != null) {
            parent.relateUnion(superType, memberType);
            return;
        }

        if (!relations.containsKey(superType.getId()))
            relations.put(superType.getId(), new ArrayList<>());
        if (!relations.containsKey(memberType.getId()))
            relations.put(memberType.getId(), new ArrayList<>());

        // Establish union inheritance
        relations.get(memberType.getId()).add(new TypeScopeRelation(
            TypeScopeRelation.Relation.UnionOf,
            superType
        ));
    }

    /**
     * Establish an inheritance relation between the given parent type and member type
     * @param superType The union type
     * @param memberType The member type
     */
    public void relateInheritance(Type superType, Type memberType) {
        // Establish relation to the top of the chain
        if (parent != null) {
            parent.relateInheritance(superType, memberType);
            return;
        }

        if (!relations.containsKey(superType.getId()))
            relations.put(superType.getId(), new ArrayList<>());
        if (!relations.containsKey(memberType.getId()))
            relations.put(memberType.getId(), new ArrayList<>());

        // Establish bi-directional relation
        relations.get(superType.getId()).add(new TypeScopeRelation(
                TypeScopeRelation.Relation.ParentOf,
                memberType
        ));
        relations.get(memberType.getId()).add(new TypeScopeRelation(
                TypeScopeRelation.Relation.ChildOf,
                superType
        ));
    }

    /**
     * Get the stream of
     * @param type The type to get relations for
     * @param relation
     * @return
     */
    public Stream<TypeScopeRelation> getRelationsOfKind(Type type, TypeScopeRelation.Relation relation) {
        List<TypeScopeRelation> typeRelations = relations.getOrDefault(type.getId(), new ArrayList<>());
        return typeRelations.stream().filter(r -> r.relation == relation);
    }

    /**
     * Define a new type
     * <p>
     * Note that every type *MUST* have a name. Auto-generated types will also
     * have a name. Even if that's just a random blob of characters.
     *
     * @param name The name of the type
     * @param typeFuture The type reference to store
     */
    public CompletableFuture<Type> define(String name, CompletableFuture<Type> typeFuture) {
        typeFuture.whenComplete((type, throwable) -> {
            if (throwable != null) {
                return;
            }
            define(name, type);
        });
        return typeFuture;
    }


    /**
     * Return the name of this scope, chaining the parent names together
     *
     * @return
     */
    public String fullName(String delimiter) {
        String name = this.name;
        if (parent != null) {
            name = parent.fullName(delimiter) + delimiter + name;
        }
        return name;
    }

    /**
     * Create a new anonymous ID, by keeping track the allocations we have done so far
     *
     * @param context The context where this ID belongs to (Ex. "array" will generate "array_123)
     * @return Returns a unique string
     */
    public String newAnonymousId(String context) {
        if (parent != null) return parent.newAnonymousId(context);

        id_counter += 1;
        return context + "_" + ((Integer) id_counter).toString();
    }

    /**
     * Import all the types from the specified scope in my scope, optionally
     * prefixing all the types with the given prefix.
     *
     * @param scope  The scope to import
     * @param prefix The prefix to add on every type
     * @return
     */
    public TypeScope importFrom(TypeScope scope, String prefix) {

        // Add this scope on the named imports
        namespaces.put(prefix, scope);

        // Check if this import is resolving some of the incomplete resolutions
        List<String> imports = new ArrayList<>(incompleteResolutions.keySet());
        imports.forEach(key -> {
            String[] parts = splitNamespace(key);
            if (parts == null) return;

            // Check if this import is targeting the namespace we just imported
            if (parts[0].equals(prefix)) {

                // Register the resolution
                scope.resolve(parts[1]).whenComplete((type, throwable) -> {
                    for (CompletableFuture<Type> f: incompleteResolutions.get(key)) {
                        if (throwable != null) {
                            f.completeExceptionally(throwable);
                            continue;
                        }
                        f.complete(type);
                    }
                    incompleteResolutions.remove(key);
                });
            }
        });

        return this;
    }

    /**
     * Shorthand to flatTypeList("")
     */
    public Map<String, Type> flatTypeMap() {
        return flatTypeMap("");
    }

    /**
     * Return a flat list of all defined types
     */
    private Map<String, Type> flatTypeMap(String prefix) {
        HashMap<String, Type> res = new HashMap<>();
        for (Map.Entry<String, Type> def : definitions.entrySet()) {
            res.put(prefix + def.getKey(), def.getValue());
        }
        for (TypeScope scope : childScopes) {
            res.putAll(scope.flatTypeMap(prefix + scope.name + "_"));
        }
        return res;
    }

    /**
     * Return a list of child scopes of the given type
     *
     * @param ofClass The type of the child scopes to return
     * @return Returns a list of child scopes that are instances of the given type
     */
    public List<TypeScope> childScopesOfType(Class<? extends TypeScope> ofClass) {
        return childScopes
                .stream()
                .filter(scope -> ofClass.isInstance(scope))
                .collect(Collectors.toList());
    }

    /**
     * Walk up the parent tree until we find a scope of the given type
     *
     * @param ofClass The type of the parent scopes to locate
     * @return Returns the TypeScope that matches the given class
     */
    public <T extends TypeScope> T parentScopeOfType(Class<T> ofClass) {
        if (parent == null) return null;
        if (ofClass.isInstance(parent)) return (T)parent;
        return parent.parentScopeOfType(ofClass);
    }
}