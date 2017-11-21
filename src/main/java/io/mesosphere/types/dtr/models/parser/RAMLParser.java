package io.mesosphere.types.dtr.models.parser;

import io.mesosphere.types.dtr.models.internal.TypeConstants;
import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeScope;
import io.mesosphere.types.dtr.models.internal.scopes.FileScope;
import io.mesosphere.types.dtr.models.internal.scopes.TypeDefinitionScope;
import io.mesosphere.types.dtr.models.internal.scopes.TypeInlineScope;
import io.mesosphere.types.dtr.models.internal.types.*;
import io.mesosphere.types.dtr.models.internal.values.*;
import io.mesosphere.types.dtr.repository.internal.RepositoryFile;
import io.mesosphere.types.dtr.repository.internal.RepositoryView;
import org.yaml.snakeyaml.Yaml;

import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * This is NOT a real RAML parser, just a simple implementation that follows roughly
 * the same specifications.
 */
public class RAMLParser {

    /**
     * A non-standard type to use when we have an object with variadic properties
     */
    static final String VARIADIC_OBJECT_TYPE = "object*";

    /**
     * Characters used by regular expression and if found in a property key
     * will convert the type into a map.
     */
    static final List<String> REGEX_CHARS = Collections.unmodifiableList(
            new ArrayList<String>() {{
                add("/");
                add(".");
                add("$");
                add("^");
                add("*");
                add("+");
                add("|");
                add("?");
                add("(");
                add(")");
                add("[");
                add("]");
                add("{");
                add("}");
            }}
    );

    /**
     * This constant contains the resolution table to the built-in type singletons.
     * Each one of the base type consists of both an annotative and structural type,
     * providing a safe failover when the user calls `getStructural` and `getAnnotative`
     * on any derived type.
     * <p>
     * Note that "object", "array" and "union" are also special types that are handled
     * separately by the `createType` function.
     */
    static final Map<String, Type> BUILTIN_TYPES = Collections.unmodifiableMap(
            new HashMap<String, Type>() {{
                put("any", TypeConstants.SCALAR_ANY);

                put("object", AnnotativeType.Builder
                        .forType(new ObjectType(null, "object"), "object")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("An object value")
                        .get());

                put(VARIADIC_OBJECT_TYPE, AnnotativeType.Builder
                        .forType(new VariadicObjectType(null, VARIADIC_OBJECT_TYPE), VARIADIC_OBJECT_TYPE)
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("A map value")
                        .get()
                    );

                put("string", AnnotativeType.Builder
                        .forType(new ScalarType((Type)null, "string", new StringValue()), "string")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("A string value")
                        .get());

                put("date-only", AnnotativeType.Builder
                        .forType(new ScalarType((Type)null, "date-only", new DateTimeValue(true, false, false)), "date-only")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("A date-only value without timezone support")
                        .get());

                put("time-only", AnnotativeType.Builder
                        .forType(new ScalarType((Type)null, "time-only", new DateTimeValue(false, true, false)), "time-only")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("A time-only value without timezone support")
                        .get());

                put("datetime-only", AnnotativeType.Builder
                        .forType(new ScalarType((Type)null, "datetime-only", new DateTimeValue(true, true, false)), "datetime-only")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("A date-and-time value without timezone support")
                        .get());

                put("datetime", AnnotativeType.Builder
                        .forType(new ScalarType((Type)null, "datetime", new DateTimeValue(true, true, true)), "datetime")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("A date-and-time value with timezone support")
                        .get());

                put("number", AnnotativeType.Builder
                        .forType(new ScalarType((Type)null, "number", new NumberValue(false)), "number")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("A floating-point number")
                        .get());

                put("integer", AnnotativeType.Builder
                        .forType(new ScalarType((Type)null, "integer", new NumberValue(true)), "integer")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("An integer number")
                        .get());

                put("boolean", AnnotativeType.Builder
                        .forType(new ScalarType((Type)null, "boolean", new BooleanValue()), "boolean")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("A boolean value")
                        .get());

                put("null", AnnotativeType.Builder
                        .forType(new ScalarType((Type)null, "null", new VoidValue()), "null")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("An empty value")
                        .get());

                put("file", AnnotativeType.Builder
                        .forType(new ScalarType((Type)null, "file", new BinaryValue(0, 2147483647, new ArrayList<String>() {{ add("*/*"); }})), "file")
                        .setBuiltin(true)
                        .setDefaultValue(null)
                        .setDescription("A binary value")
                        .get());
            }}
    );

    /**
     * This is a helper class used by the `loadProjectTypes` method in order
     * to keep track of the `uses` statements.
     */
    private static class UsesResolution {
        /**
         * Where to inject the uses
         */
        TypeScope scope;

        /**
         * The prefix of the imported class
         */
        String prefix;

        /**
         * The name of the import
         */
        String filename;

        /**
         * Construct a `uses` resolution
         *
         * @param scope    The scope where to inject the types
         * @param prefix   The prefix to use when importing the types
         * @param filename The filename to import the types from
         */
        UsesResolution(TypeScope scope, String prefix, String filename) {
            this.scope = scope;
            this.prefix = prefix;
            this.filename = filename;
        }
    }


    /**
     * A share dcounter is a synchronisation primitive that calls an
     * end condition task when a counter value reaches zero.
     */
    private class SharedCounter {

        /**
         * The current value of the counter
         */
        int counter;

        /**
         * The task to execute when the end condition is met
         */
        Runnable endCondition;

        /**
         * Initialize a shared counter
         * @param counter The initial value of the counter
         * @param endCondition The runnable task to execute when the end condition is met
         */
        public SharedCounter(int counter, Runnable endCondition) {
            this.counter = counter;
            this.endCondition = endCondition;
        }

        /**
         * Decrement the counter by 1
         */
        synchronized public void decrement() {
            counter -= 1;
            if (counter == 0) {
                endCondition.run();
            }
        }

    }

    /**
     * Returns true if any of the keys in the properties contains regular expression chars
     * @param properties The properties to check
     * @return Returns True if there are properties with regular expressions
     */
    public Boolean hasRegexProperties(Map<String, Object> properties) {
        if (properties == null) {
            return false;
        }

        // Check each property
        for (Map.Entry<String, Object> p: properties.entrySet()) {
            String key = p.getKey();

            // Note that optional properties end to "?". Therefore the character
            // should not be accounted for regex detection.
            if (key.endsWith("?")) key = key.substring(0, key.length()-1);

            if (REGEX_CHARS.parallelStream().anyMatch(key::contains)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Guess base type based on the fields found, according to :
     * https://github.com/raml-org/raml-spec/blob/master/versions/raml-10/raml-10.md#determine-default-types
     *
     * @param typeDefinition The fields in the RAML Type definition
     */
    public Object guessType(Map<String, Object> typeDefinition) {
        Object type = "string";
        if (typeDefinition.containsKey("properties")) type = "object";
        if (typeDefinition.containsKey("type")) type = typeDefinition.get("type");

        // Note that "null" is a legit YAML expression and is going to return
        // a "null" pointer. Thus we must convert this into a string in order
        // to handle it properly.
        if (type == null) type = "null";

        // If we have an object and the object contains regular expression
        // keys we must change the type to map
        if ("object".equals(type) && typeDefinition.containsKey("properties")) {
            Object properties = typeDefinition.get("properties");
            if (properties instanceof Map) {
                if (hasRegexProperties((Map<String, Object>) properties)) {
                    type = VARIADIC_OBJECT_TYPE;
                }
            }
        }

        return type;
    }

    /**
     * Create a type from the definition object passed
     * Note that the `scope` should be named as the type to be generated
     *
     * @param typeDefinition The type definition to process (string or map)
     * @param scope The scope where to create this type
     * @return Returns a future with the type that will be resolved when all of it's components are resolved
     */
    public CompletableFuture<Type> createType(Object typeDefinition, TypeScope scope) {
        if (typeDefinition instanceof String) {
            return createType((String)typeDefinition, scope);
        } else if (typeDefinition instanceof  Map) {
            Map<String, Object> typeMap = (Map<String, Object>) typeDefinition;

            // Collapse type definitions that contain only the `type` facet into a
            // string-like expressions
            if (typeMap.size() == 1 && typeMap.containsKey("type")) {
                return createType(typeMap.get("type"), scope);
            }

            // Otherwise compile a map-type
            return createType(typeMap, scope);
        } else {
            CompletableFuture<Type> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("Trying to compile a type from an unsupported definition structure"));
            return future;
        }
    }

    /**
     * Create a type from the definition expression string passed
     * <p>
     * According to RAML specifications we have to support the following expressions:
     * https://github.com/raml-org/raml-spec/blob/master/versions/raml-10/raml-10.md#type-expressions
     * <p>
     * - "type"              : Resolve the specified base type or user-defined type
     * - "type[]"            : An anonymous array of the given type
     * - "type[][].."        : Anonymous arrays can be expanded to multiple dimensions
     * - "typeA | typeB"     : A Union of two or more types, differentiated by a discriminator
     * - "(typeA | typeB)[]" : An array of an anonymous union of these two
     *
     * @param typeExpression The type expression to process
     * @param scope The scope where to create this type
     * @return Returns a future with the type that will be resolved when all of it's components are resolved
     */
    public CompletableFuture<Type> createType(String typeExpression, TypeScope scope) {
        // Sanitize input
        typeExpression = typeExpression.trim();

        // Remove obvious parenthesis
        if (typeExpression.startsWith("(") && typeExpression.endsWith(")")) {
            typeExpression = typeExpression.substring(1, typeExpression.length() - 1).trim();
        }

        //
        // Array expression
        //
        if (typeExpression.endsWith("[]")) {

            // Create a new inline scope for the in-line definition
            TypeScope inlineScope = new TypeInlineScope(scope, "array");

            // Get the expression we are converting to array
            String baseExpression = typeExpression.substring(0, typeExpression.length() - 2);
            CompletableFuture<Type> baseType = createType(baseExpression, inlineScope);

            // Convert base type to array, using the built-in array
            // as our base class.
            return structCreateArray(
                CompletableFuture.completedFuture(BUILTIN_TYPES.get("array")),
                baseType,
                scope
            );

        }

        //
        // Union expression
        //
        else if (typeExpression.contains("|")) {

            // Create a new inline scope for the in-line definition
            TypeScope inlineScope = new TypeInlineScope(scope, "union");

            // Extract the union type expressions
            final String[] unionTypeExpressions = typeExpression.split("\\s*\\|\\s*");

            // Create the union
            return this.structCreateUnion(
                CompletableFuture.completedFuture(BUILTIN_TYPES.get("array")),
                Arrays.stream(unionTypeExpressions)
                    .map(s -> createType(s, inlineScope))
                    .collect(Collectors.toList()),
                scope
            );
        }

        //
        // Resolve built-in types
        //
        else if (BUILTIN_TYPES.containsKey(typeExpression)) {
            return CompletableFuture.completedFuture(BUILTIN_TYPES.get(typeExpression));
        }

        //
        // Resolve types in scope
        //
        else {
            return scope.resolve(typeExpression);
        }
    }

    /**
     * Create a type from the definition map passed
     *
     * @param typeDefinition The type definition map to process
     * @param scope The scope where to create this type
     * @return Returns a future with the type that will be resolved when all of it's components are resolved
     */
    public CompletableFuture<Type> createType(Map<String, Object> typeDefinition, TypeScope scope) {
        try {
            // Create an inner scope that we are going to use for resolving in-line types
            TypeScope innerScope = new TypeInlineScope(scope, "type");

            // Guess the type or return the value of `type`
            Object baseTypeExpression = guessType(typeDefinition);
            CompletableFuture<Type> baseType;

            //
            // [Array] Explicit `array` definition
            //
            if ("array".equals(baseTypeExpression)) {

                // The RAML specification does not really explain what happens
                // if the `items` property is missing. It could either mean "use default"
                // which would result in using "string" as default (since we assume that
                // everything is string unless a "properties" facet exists, in which case
                // it's an object), or it could mean "there is no restriction on the type"
                // in which case we should use "any".
                //
                // I chose the first, since "any" is not a good candidate when defining
                // strictly typed APIs.
                //
                Object itemTypeExpression = typeDefinition.getOrDefault("items", "string");

                // Create the structural array type
                CompletableFuture<Type> structType = structCreateArray(
                        CompletableFuture.completedFuture(BUILTIN_TYPES.get("array")),
                        createType(itemTypeExpression, innerScope),
                        scope
                );

                // If we have `enum` wrap this struct type with an enum struct type
                if (typeDefinition.containsKey("enum")) {
                    structType = structCreateEnum(
                            CompletableFuture.completedFuture(null),
                            structType,
                            getEnumValues(typeDefinition),
                            scope
                    );
                }

                // Annotate structural type if there are annotative facets
                return annotateType(structType, typeDefinition, scope);

            }

            //
            // [Object] Explicit `object` or variadic object definition
            //
            else if ("object".equals(baseTypeExpression) || VARIADIC_OBJECT_TYPE.equals(baseTypeExpression)) {

                // Resolve types of object properties
                Map<String, ObjectPropertyType> propertyFutures = new HashMap<>();

                // If we have valid properties, resolve the types
                if (typeDefinition.containsKey("properties")) {

                    // Resolve types for each property
                    propertyFutures = resolveProperties(typeDefinition.get("properties"), innerScope);

                    // Fail if the function returned `null` (which means that invalid arguments were given)
                    if (propertyFutures == null) {
                        CompletableFuture<Type> future = new CompletableFuture<>();
                        future.completeExceptionally(new IllegalArgumentException("The `properties` object must always be a map"));
                        return future;
                    }
                }

                // Create variadic object or structural object
                CompletableFuture<Type> structType;
                if (VARIADIC_OBJECT_TYPE.equals(baseTypeExpression)) {
                    structType = structCreateVariadicObject(
                            CompletableFuture.completedFuture(BUILTIN_TYPES.get(VARIADIC_OBJECT_TYPE)),
                            propertyFutures,
                            scope
                    );
                } else {
                    structType = structCreateObject(
                            CompletableFuture.completedFuture(BUILTIN_TYPES.get("object")),
                            propertyFutures,
                            scope
                    );
                }

                // If we have `enum` wrap this struct type with an enum struct type
                if (typeDefinition.containsKey("enum")) {
                    structType = structCreateEnum(
                            CompletableFuture.completedFuture(null),
                            structType,
                            getEnumValues(typeDefinition),
                            scope
                    );
                }

                // Annotate structural type if there are annotative facets
                return annotateType(structType, typeDefinition, scope);

            }

            //
            // [Extend] Extension of an existing base type
            //
            else {

                // Resolve the base type we are extending
                baseType = createType(baseTypeExpression, innerScope);
                CompletableFuture<Type> extendedType = baseType;

                //
                // [Object] Check if we are structurally altering an object
                //
                if (typeDefinition.containsKey("properties")) {

                    // Resolve types for each property
                    Map<String, ObjectPropertyType> propertyFutures = resolveProperties(
                            typeDefinition.get("properties"), scope
                    );

                    // Fail if the function returned `null` (which means that invalid arguments were given)
                    if (propertyFutures == null) {
                        CompletableFuture<Type> future = new CompletableFuture<>();
                        future.completeExceptionally(new IllegalArgumentException("The `properties` property must always be a map"));
                        return future;
                    }

                    // Extend object
                    extendedType = structExtendObject(baseType, propertyFutures, hasRegexProperties((Map<String, Object>) typeDefinition.get("properties")), scope);

                }

                //
                // [Array] Check if we are structurally altering an array
                //
                else if (typeDefinition.containsKey("items")) {
                    CompletableFuture<Type> itemTypeFuture = createType(typeDefinition.get("items"), innerScope);

                    // Create a new array type, extending the base one
                    extendedType = structCreateArray(
                            baseType,
                            itemTypeFuture,
                            scope
                    );
                }

                //
                // [Enum] If we have `enum` wrap this struct type with an enum struct type
                //
                if (typeDefinition.containsKey("enum")) {
                    extendedType = structExtendEnum(
                            extendedType,
                            getEnumValues(typeDefinition),
                            scope
                    );
                }

                // Annotate the base type with the facets from the type definition
                return annotateType(
                        extendedType,
                        typeDefinition,
                        scope
                );

            }
        }

        // Catch all errors that are generate by the utility functions,
        // for example `getEnumValues`
        catch (IllegalArgumentException e) {
            CompletableFuture<Type> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    /**
     * Try to extract the value of `enum` property and return it, or `null` if missing or type mismatch
     * @param typeDefinition The type definition from which to extract the value
     * @return Returns the values or `null`
     */
    public List<Object> getEnumValues(Map<String, Object> typeDefinition) throws IllegalArgumentException {
        Object value = typeDefinition.getOrDefault("enum", null);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Enums only accept lists for their values");
        }

        return (List<Object>) value;
    }

    /**
     * Create a union structural type
     *
     * @param baseTypeFuture The future that will resolve to the baseType
     * @param unionTypeFuture The list of futures that will resolve to each union component
     * @param scope The scope where to define this union
     * @return Returns a future that will be resolved to the union structure
     */
    public CompletableFuture<Type> structCreateUnion(CompletableFuture<Type> baseTypeFuture, List<CompletableFuture<Type>> unionTypeFuture, TypeScope scope) {
        CompletableFuture<Type> future = new CompletableFuture<>();
        ArrayList<Type> unionTypes = new ArrayList<>();

        // Create a counter that counts down until the futures are resolved
        SharedCounter counter = new SharedCounter(unionTypeFuture.size(), () -> {
            baseTypeFuture.whenComplete((baseType, throwable) -> {
                Type extendedType;
                if (throwable != null) {
                    future.completeExceptionally(throwable);
                    return;
                }

                // Create a union type
                extendedType = scope.defineDefault(
                    new UnionType(
                        baseType,
                        scope.name,
                        unionTypes
                    )
                );

                // Establish relations between types
                unionTypes.forEach(type -> {
                    scope.relateUnion(extendedType, type);
                });

                future.complete(extendedType);

            });
        });

        // Wait until each type is resolved
        unionTypeFuture.forEach(typeCompletableFuture -> {
            typeCompletableFuture.whenComplete((type, throwable) -> {
                if (throwable != null) {
                    future.completeExceptionally(throwable);
                    return;
                }

                // Track the type instances of the resolved union types
                unionTypes.add(type);
                counter.decrement();
            });
        });

        // Return the future that will be completed ... in the future ;)
        return future;
    }


    /**
     * Create a new structural array type
     *
     * @param baseTypeFuture The base type future
     * @param itemTypeFuture The item type future
     * @param scope The scope where to create the array
     * @return Returns the future that will resolve to the array type
     */
    public CompletableFuture<Type> structCreateArray(CompletableFuture<Type> baseTypeFuture, CompletableFuture<Type> itemTypeFuture, TypeScope scope) {
        // When base and item types are resolved create the array type
        return CompletableFuture.completedFuture(
            scope.defineDefault(
                new ArrayType(
                    baseTypeFuture,
                    scope.name,
                    itemTypeFuture
                )
            )
        );
    }

    /**
     * Creates an enum structural type from the value type and values given
     * @param baseTypeFuture The base type that we are extending
     * @param valueTypeFuture The type of the values
     * @param values The values of the enum values
     * @return Returns a future that will be completed to the enum type when the value type is resolved
     */
    public CompletableFuture<Type> structCreateEnum(CompletableFuture<Type> baseTypeFuture, CompletableFuture<Type> valueTypeFuture, List<Object> values, TypeScope scope) {
        // If there are no values, pass-through the evaluation
        if (valueTypeFuture == null) {
            return baseTypeFuture;
        }

        // Otherwise wait for the types to be resolved and create the enum type
        return CompletableFuture.completedFuture(
            scope.defineDefault(
                new EnumType(
                    baseTypeFuture,
                    scope.name,
                    valueTypeFuture,
                    values
                )
            )
        );
    }

    /**
     * Parses a `properties` YAML object and creates the futures to each property
     *
     * @param propertiesRef An object that contains the properties map (if anything else is given the function returns null)
     * @param innerScope The scope to use for defining in-line types
     * @return Returns the properties with a future that will be resolved when the type is resolved
     */
    public Map<String, ObjectPropertyType> resolveProperties(Object propertiesRef, TypeScope innerScope) {
        // First validate type and if the type is wrong, bail early
        if (!(propertiesRef instanceof Map)) {
            return null;
        }

        // Convert property expressions to type futures
        Map<String, Object> properties = (Map<String, Object>) propertiesRef;
        return properties.entrySet().stream()
            .collect(Collectors.toMap(
                keyValue -> {
                    String key = keyValue.getKey();
                    if (key.endsWith("?")) return key.substring(0, key.length()-1);
                    return key;
                },
                keyValue -> {
                    Object value = keyValue.getValue();
                    CompletableFuture<ObjectPropertyType> propFuture = new CompletableFuture<>();

                    // Check if the property is required
                    Boolean isRequired = !keyValue.getKey().endsWith("?");
                    if (value instanceof Map && ((Map) value).containsKey("required")) {
                        isRequired = (Boolean)((Map) value).get("required");
                    }

                    // Create a property object type
                    return new ObjectPropertyType(
                        createType(value, innerScope),
                        keyValue.getKey(),
                        isRequired,
                        null
                    );
                }
            ));
    }

    /**
     * Create a new structiral variadic object type
     * @param baseTypeFuture The base type future
     * @param propertyTypes The object properties and the futures for each one of the property type
     * @param scope The scope where to create the object
     * @return Returns the future that will resolve to the object type
     */
    public CompletableFuture<Type> structCreateVariadicObject(CompletableFuture<Type> baseTypeFuture, Map<String, ObjectPropertyType> propertyTypes, TypeScope scope) {
        // Create variadic object
        return CompletableFuture.completedFuture(
            scope.defineDefault(
                new VariadicObjectType(
                    baseTypeFuture,
                    scope.name,
                    propertyTypes
                )
            )
        );
    }

    /**
     * Create a new structural object type
     *
     * @param baseTypeFuture The base type future
     * @param propertyTypes The object properties and the futures for each one of the property type
     * @param scope The scope where to create the object
     * @return Returns the future that will resolve to the object type
     */
    public CompletableFuture<Type> structCreateObject(CompletableFuture<Type> baseTypeFuture, Map<String, ObjectPropertyType> propertyTypes, TypeScope scope) {
        CompletableFuture<Type> future = new CompletableFuture<>();
        CompletableFuture<Type> structuralBaseTypeFuture = new CompletableFuture<>();

        // Create a union type
        future.complete(scope.defineDefault(
            new ObjectType(
                baseTypeFuture,
                scope.name,
                propertyTypes
            )
        ));

        // Return the future that will be completed ... in the future ;)
        return future;
    }

    /**
     * Extend an existing object structural type
     *
     * @param baseTypeFuture The base type future to extend
     * @param propertyTypes The object properties
     * @param scope The scope where to create the object
     * @return Returns the future that will resolve to the object type
     */
    public CompletableFuture<Type> structExtendObject(CompletableFuture<Type> baseTypeFuture, Map<String, ObjectPropertyType> propertyTypes, Boolean hasRegexProps, TypeScope scope) {
        return baseTypeFuture.thenApply(baseType -> {
            Type extendedType;

            // If this type is annotative, keep a reference of the annotation type
            // that will be placed back before completing the future. Failing to do
            // so will loose the annotative information since `getStructural` could
            // jump back the tree a few steps
            AnnotativeType annotativeBase = null;
            if (baseType instanceof AnnotativeType) {
                annotativeBase = (AnnotativeType) baseType;
            }

            // Make sure the base class is an extensible object
            StructuralType structuralBaseType = baseType.getStructural();
            if (!(structuralBaseType instanceof ObjectType) && !(structuralBaseType instanceof VariadicObjectType)) {
                throw new IllegalArgumentException("Trying to create an object by extending an non-object base class");
            }

            // Check if the base type is a variadic object
            if (structuralBaseType instanceof VariadicObjectType) {

                // Create new variadic object by extending the base type
                extendedType = scope.defineDefault(annotateType(
                        ((VariadicObjectType) structuralBaseType).extendClone(
                            scope.name,
                            propertyTypes
                        ),
                        annotativeBase
                ));

                // Define relations with the parent
                scope.relateInheritance(baseType, extendedType);

                return extendedType;
            }

            // Check if the base type is an object, yet we were extending it with regex
            // properties, effectively converting this to a map
            if (hasRegexProps /* && structuralBase instanceof ObjectType (implied) */) {

                // Cast the base object type to variadic
                VariadicObjectType vobj = VariadicObjectType.fromObjectType((ObjectType)structuralBaseType);

                // Create new variadic object by extending the base
                extendedType = scope.defineDefault(annotateType(
                        vobj.extendClone(
                            scope.name,
                            propertyTypes
                        ),
                        annotativeBase
                ));

                // Define relations with the parent
                scope.relateInheritance(baseType, extendedType);

                return extendedType;
            }

            // Otherwise extend the object type
            extendedType = scope.defineDefault(annotateType(
                    ((ObjectType)structuralBaseType).extendClone(scope.name, propertyTypes),
                    annotativeBase
            ));

            // Define relations with the parent
            scope.relateInheritance(baseType, extendedType);

            return extendedType;

        });
    }

    /**
     * Tries to extend the given base type and if it's not an enum create a new enum
     *
     * @param baseTypeFuture The base type that we are extending
     * @param values The values of the enum values
     * @return Returns a future that will be completed to the enum type when the value type is resolved
     */
    public CompletableFuture<Type> structExtendEnum(CompletableFuture<Type> baseTypeFuture, List<Object> values, TypeScope scope) {
        return baseTypeFuture.thenApply(baseType -> {
            Type extendedType;

            // If this type is annotative, keep a reference of the annotation type
            // that will be placed back before completing the future. Failing to do
            // so will loose the annotative information since `getStructural` could
            // jump back the tree a few steps
            AnnotativeType annotativeBase = null;
            if (baseType instanceof AnnotativeType) {
                annotativeBase = (AnnotativeType) baseType;
            }

            // Get the structural base to extend
            Type structBaseType = baseType.getStructural();

            // If the base type that we are extending is already an enum, then
            // carry along the initial enum values
            if (structBaseType instanceof EnumType) {

                // Merge base and current values
                ArrayList<Object> allValues = new ArrayList<>();
                allValues.addAll(((EnumType) structBaseType).values);
                allValues.addAll(values);

                // Create an enum with the base and new values
                extendedType = scope.defineDefault(
                    annotateType(new EnumType(
                        null,
                        scope.name,
                        structBaseType,
                        allValues
                    ), annotativeBase)
                );
            }

            else {
                // Otherwise create a new enum
                extendedType = scope.defineDefault(
                    annotateType(new EnumType(
                        null,
                        scope.name,
                        baseType,
                        values
                    ), annotativeBase)
                );
            }

            // Define relations with the parent
            scope.relateInheritance(baseType, extendedType);

            return extendedType;
        });
    }

    /**
     * Create an annotative super-type for the specified type, copying the annotative information
     * from the type given.
     *
     * @param type The type to annotate
     * @param annotative The annotation to copy
     * @return Returns the type give, annotated with a copy of the annotative information given
     */
    public Type annotateType(Type type, AnnotativeType annotative) {
        // If we don't have annotative information, return the base type
        if (annotative == null) {
            return type;
        };

        // Otherwise annotate it with a copy
        return AnnotativeType.Builder.forType(type, type.getId())
                .copyFrom(annotative)
                .get();
    }

    /**
     * Create an annotative super-type for the specified type, parsing the facets provided and also
     * wrap the entire type into an enum type if there is an `enum` facet.
     *
     * If there are no facets that could annotate the type the same future is returned without
     * any modification.
     *
     * @param itemTypeFuture The type to annotate
     * @param facets The map from which to extract facets and other metadata
     * @return The annotation type that encapsulates `itemType`
     */
    public CompletableFuture<Type> annotateType(CompletableFuture<Type> itemTypeFuture, Map<String, Object> facets, TypeScope scope) {
        return itemTypeFuture.thenApply(type ->  {
            AnnotativeType.Builder builder = AnnotativeType.Builder.forType(type, scope.name).copyFrom(type.getAnnotative());
            Boolean isAnnotated = false;

            if (facets.containsKey("default")) {
                isAnnotated = true;
                builder.setDefaultValue(facets.get("default"));
            }
            if (facets.containsKey("description")) {
                isAnnotated = true;
                builder.setDescription(facets.get("description").toString());
            }
            if (facets.containsKey("example")) {
                isAnnotated = true;
                builder.setExample(facets.get("example"));
            }
            if (facets.containsKey("required")) {
                isAnnotated = true;
                builder.setRequired((Boolean)facets.get("required"));
            }
            if (facets.containsKey("uniqueItems")) {
                isAnnotated = true;
                builder.setUniqueItems((Boolean)facets.get("uniqueItems"));
            }
            if (facets.containsKey("minItems")) {
                isAnnotated = true;
                builder.setMinItems((Integer) facets.get("minItems"));
            }
            if (facets.containsKey("maxItems")) {
                isAnnotated = true;
                builder.setMaxItems((Integer) facets.get("maxItems"));
            }
            if (facets.containsKey("additionalProperties")) {
                isAnnotated = true;
                builder.setAdditionalProperties((Boolean)facets.get("additionalProperties"));
            }
            if (facets.containsKey("minProperties")) {
                isAnnotated = true;
                builder.setMinProperties((Integer) facets.get("minProperties"));
            }
            if (facets.containsKey("maxProperties")) {
                isAnnotated = true;
                builder.setMaxProperties((Integer) facets.get("maxProperties"));
            }

            // If this type was not annotated, return the original type
            if (isAnnotated) {
                return builder.get();
            } else {
                return type;
            }
        });
    }
//
//    /**
//     * Iterate over the given map of properties and check if they all share
//     * the same type. If yes, return that type, otherwise return null.
//     *
//     * @param properties The map of properties to check
//     * @return Returns `null` if properties are different or the common property type
//     */
//    public CompletableFuture<Type> getCommonPropertyType(Map<String, ObjectPropertyType> properties) {
//        return getCommonPropertyType(properties, CompletableFuture.completedFuture(null));
//    }
//
//    /**
//     * Iterate over the given map of properties and check if they all share
//     * the same type. If yes, return that type, otherwise return null.
//     *
//     * @param properties The map of properties to check
//     * @param startingTypeFuture The first type to match everything else against
//     * @return Returns `null` if properties are different or the common property type
//     */
//    public CompletableFuture<Type> getCommonPropertyType(Map<String, ObjectPropertyType> properties, CompletableFuture<Type> startingTypeFuture) {
//        final CompletableFuture<Type> future = new CompletableFuture<>();
//
//        // Wait for the starting type to be defined
//        startingTypeFuture.whenComplete((startingType, throwable) -> {
//            if (throwable != null) {
//                future.completeExceptionally(throwable);
//                return;
//            }
//
//            // Wait for the futures of all properties to complete
//            io.mesosphere.types.dtr.utils.Futures.waitForAll(
//                    properties.values().stream()
//                            .map(objectPropertyType -> objectPropertyType.typeFuture)
//                            .collect(Collectors.toList())
//            ).whenComplete((types, throwable1) -> {
//                if (throwable1 != null) {
//                    future.completeExceptionally(throwable1);
//                    return;
//                }
//
//                // Check if all types are equal
//                Type commonType = startingType;
//                for (Type type: types) {
//
//                    // Set the first value
//                    if (commonType == null) {
//                        commonType = type;
//                    }
//
//                    // If the common type is already `any` we cannot get broader,
//                    // complete the future right away
//                    else if (commonType == BUILTIN_TYPES.get("any")) {
//                        future.complete(commonType);
//                        return;
//                    }
//
//                    // If there is no common type, fall back to "any"
//                    else if (!commonType.equals(type)) {
//                        future.complete(BUILTIN_TYPES.get("any"));
//                        return;
//                    }
//                }
//
//                // Otherwise complete the future with the common type
//                future.complete(commonType);
//            });
//        });
//
//        // Return the future that will resolve to the common type
//        return future;
//    }

    /**
     * Create a new type with the given name and type definition.
     */
    private CompletableFuture<Type> createNamedType(String name, Object typeDefinition, TypeScope scope) {
        TypeScope typeScope = new TypeDefinitionScope(scope, name);
        CompletableFuture<Type> type = createType(typeDefinition, typeScope);

        // Otherwise just define this base type under the given name
        return scope.define(name, type);
    }

    /**
     * Parse all the types from the specified project into the type registry
     *
     * @param project     The project view that contains the type files
     * @param globalScope The scope where to insert the types into
     */
    public void loadProjectTypes(RepositoryView project, TypeScope globalScope) {
        Map<String, TypeScope> fileScopes = new HashMap<>();
        List<UsesResolution> usesResolutions = new ArrayList<>();
        Yaml yaml = new Yaml();

        for (RepositoryFile type : project.listFiles()) {
            try {

                // Handle only RAML files
                if (!type.getMimeType().equals("application/raml+yaml")) {
                    continue;
                }

                // Create a file scope where the types are going to be defined
                TypeScope fileScope = new FileScope(globalScope, type.getName());

                // Keep track of file scopes for later `uses` resolution
                fileScopes.put(type.getName(), fileScope);

                // Load the YAML definition from the file contents
                Object doc = yaml.load(project.openFile(type.getName()));
                HashMap<String, Object> ddoc = (HashMap<String, Object>) doc;

                // Process `uses` entries
                if (ddoc.containsKey("uses")) {
                    //
                    // For every "uses" entry, create a resolution record that
                    // we are going to walk through when everything is loaded.
                    //
                    for (Map.Entry<String, String> usesRecord : ((Map<String, String>) ddoc.get("uses")).entrySet()) {
                        usesResolutions.add(
                                new UsesResolution(
                                        fileScope,
                                        usesRecord.getKey(),
                                        usesRecord.getValue()
                                )
                        );
                    }
                }

                // Process `types` entries
                if (ddoc.containsKey("types")) {
                    for (Map.Entry<String, Object> typeRecord : ((Map<String, Object>) ddoc.get("types")).entrySet()) {
                        CompletableFuture<Type> ttype = this.createNamedType(
                                typeRecord.getKey(),
                                typeRecord.getValue(),
                                fileScope
                        );

                        ttype.whenComplete((ttype1, throwable) -> {
                            if (throwable != null) {
                                throwable.printStackTrace();
                                return;
                            }
                        });
                    }
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

        // Resolve `uses`
        for (UsesResolution resolve : usesResolutions) {

            // Make sure we have the file requested
            if (!fileScopes.containsKey(resolve.filename)) {
                throw new IllegalArgumentException(
                        String.format(
                                "%s uses imports from %s but file was not found",
                                resolve.scope.name, resolve.filename
                        )
                );
            }

            // Import namespaced variables from the scope with the given filename
            resolve.scope.importFrom(
                    fileScopes.get(resolve.filename),
                    resolve.prefix
            );
        }

    }


}
