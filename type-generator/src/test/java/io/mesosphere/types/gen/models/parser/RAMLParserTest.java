package io.mesosphere.types.gen.models.parser;

import io.mesosphere.types.gen.models.internal.TypeScopeRelation;
import org.junit.Test;
import io.mesosphere.types.gen.models.internal.Type;
import io.mesosphere.types.gen.models.internal.TypeScope;
import io.mesosphere.types.gen.models.internal.scopes.GlobalScope;
import io.mesosphere.types.gen.models.internal.types.*;
import org.yaml.snakeyaml.Yaml;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;

public class RAMLParserTest {

    /**
     * A few re-usable type names
     */
    private static final Map<String, ObjectPropertyType> ObjectAProps = new HashMap<String, ObjectPropertyType>() {{
        put("foo", new ObjectPropertyType(
                RAMLParser.BUILTIN_TYPES.get("string"),
                "foo",
                false,
                null
        ));
    }};
    private static final ObjectType ObjectA = new ObjectType(RAMLParser.BUILTIN_TYPES.get("object"), "ObjectA", ObjectAProps);
    private static final ObjectType ObjectB = new ObjectType(RAMLParser.BUILTIN_TYPES.get("object"), "ObjectB");
    private static final ObjectType ObjectC = new ObjectType(RAMLParser.BUILTIN_TYPES.get("object"), "ObjectC");
    private static final ArrayType ArrayA = new ArrayType(RAMLParser.BUILTIN_TYPES.get("array"), "ArrayA", ObjectA);
    private static final VariadicObjectType VarObjectA = new VariadicObjectType(RAMLParser.BUILTIN_TYPES.get(RAMLParser.VARIADIC_OBJECT_TYPE), "VarObjectA");
    private static final EnumType EnumA = new EnumType(null, "EnumA", RAMLParser.BUILTIN_TYPES.get("string"), Arrays.asList("foo", "bar"));

    private static final AnnotativeType AObjectA = AnnotativeType.Builder.forType(ObjectA, "AObjectA").setDescription("An object of type ObjectA").get();
    private static final AnnotativeType AArrayA = AnnotativeType.Builder.forType(ArrayA, "AArrayA").setDescription("An array of typeA").get();
    private static final AnnotativeType AEnumA = AnnotativeType.Builder.forType(EnumA, "AEnumA").setDescription("An enumeration of strings").get();

    /**
     * Parse a YAML object into a java map
     *
     * @param contents The contents of the YAML document
     * @return
     */
    private Map<String, Object> parseYamlObject(String contents) {
        Yaml yaml = new Yaml();
        return yaml.load(contents);
    }

    /**
     * Generate a test scope
     *
     * @return
     */
    private TypeScope generateTestScope() {
        TypeScope scope = new GlobalScope();
        scope.define("ObjectA", ObjectA);
        scope.define("AObjectA", AObjectA);
        scope.define("ObjectB", ObjectB);
        scope.define("ObjectC", ObjectC);
        scope.define("ArrayA", ArrayA);
        scope.define("AArrayA", AArrayA);
        scope.define("VarObjectA", VarObjectA);
        scope.define("EnumA", EnumA);
        scope.define("AEnumA", AEnumA);
        return scope;
    }

    /**
     * Tests if circular dependency is correctly resolved
     * <p>
     * Here we are testing a simple case:
     * <p>
     * - `CircularB` is creating a type that extends an unknown
     * type, therefore is held in a incomplete state.
     * <p>
     * - `CircularA` is creating an array type whose `itemType`
     * points to the incomplete `CircularB`
     * <p>
     * - Since `ArrayType` does not require other information to
     * be completed, the type will be completed.
     * <p>
     * - The completion of `CircularB` will complete the definition
     * of `CircularA`
     *
     * @throws Exception
     */
    @Test
    public void circularDependencyArray() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fCircularA, fCircularB;
        Map<String, Object> yaml;

        // Define first type
        yaml = parseYamlObject("type: CircularB\n");
        fCircularA = parser.createType(yaml, scope);
        scope.define("CircularA", fCircularA);
        assertEquals(fCircularA.isDone(), false);

        // Define second type
        yaml = parseYamlObject("type: CircularA[]\n");
        fCircularB = parser.createType(yaml, scope);
        scope.define("CircularB", fCircularB);
        assertEquals(fCircularB.isDone(), true);
        assertEquals(fCircularA.isDone(), true);
    }

    /**
     * Tests the detection of regular expressions in the properties
     * @throws Exception
     */
    @Test
    public void hasRegexProperties() throws Exception {
        RAMLParser parser = new RAMLParser();
        Map<String, Object> yaml;

        // Negative check
        yaml = parseYamlObject("prop1: string\nprop2: integer\n");
        assertFalse(parser.hasRegexProperties(yaml));

        // Positive check
        yaml = parseYamlObject("prop1: string\n/.*/: integer\n");
        assertTrue(parser.hasRegexProperties(yaml));

        // Some corner-case positive check
        yaml = parseYamlObject("prop1: string\nexpr+: integer\n");
        assertTrue(parser.hasRegexProperties(yaml));
    }

    /**
     * Test the determination of default type
     * https://github.com/raml-org/raml-spec/blob/master/versions/raml-10/raml-10.md#determine-default-types
     */
    @Test
    public void testGuessType() {
        RAMLParser parser = new RAMLParser();

        // By default, if type and properties is missing, the type should be "string"
        assertEquals("string", parser.guessType(parseYamlObject("some: type\n")));

        // If properties exist, it should be an object
        assertEquals("object", parser.guessType(parseYamlObject("properties:\n")));
        assertEquals("object", parser.guessType(parseYamlObject("properties:\n  some: object\n")));

        // If we have "type", it take precedence
        assertEquals("ObjectA", parser.guessType(parseYamlObject("type: ObjectA\n")));
        assertEquals("ObjectA", parser.guessType(parseYamlObject("type: ObjectA\nproperties:\n  some: object\n")));

        // If we have regex properties the type is converted
        // into an internal type that represents "map"
        assertEquals(RAMLParser.VARIADIC_OBJECT_TYPE, parser.guessType(parseYamlObject("properties:\n  /.*/: string\n")));
        assertEquals(RAMLParser.VARIADIC_OBJECT_TYPE, parser.guessType(parseYamlObject("type: object\nproperties:\n  /.*/: string\n")));
    }

    /**
     * Test if the built-ins are resolved correctly
     * https://github.com/raml-org/raml-spec/blob/master/versions/raml-10/raml-10.md#built-in-types
     */
    @Test
    public void testBuiltinTypes() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> type;

        // The built-in type names to check
        final String[] builtinTypes = new String[]{
                "any", "time-only", "datetime", "datetime-only", "date-only",
                "number", "integer", "boolean", "string", "null", "file"
        };

        // Test each type
        for (String typeName : builtinTypes) {
            type = parser.createType(parseYamlObject("type: " + typeName + "\n"), scope);
            assertEquals(RAMLParser.BUILTIN_TYPES.get(typeName), type.get(1, TimeUnit.SECONDS));
        }
    }

    /**
     * Test if exceptions are correctly generated when parsing invalid data
     */
    @Test
    public void testExceptions() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Map<String, Object> yaml;
        Type type;

        // Should throw an exception if object property is not map
        yaml = parseYamlObject("type: object\nproperties: [should, be, map]");
        fType = parser.createType(yaml, scope);
        try {
            type = fType.get(1, TimeUnit.SECONDS);
            fail("An `IllegalArgumentException` should be thrown");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
            assertEquals(e.getCause().getMessage(), "The `properties` object must always be a map");
        }

        // Should throw an exception if enum property is not list
        yaml = parseYamlObject("type: string\nenum: da-string");
        fType = parser.createType(yaml, scope);
        try {
            type = fType.get(1, TimeUnit.SECONDS);
            fail("An `IllegalArgumentException` should be thrown");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
            assertEquals(e.getCause().getMessage(), "Enums only accept lists for their values");
        }

        // Should throw an exception if extending non-object type
        yaml = parseYamlObject("type: string\nproperties:\n  foo: string\n  bar: string");
        fType = parser.createType(yaml, scope);
        try {
            type = fType.get(1, TimeUnit.SECONDS);
            fail("An `IllegalArgumentException` should be thrown");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
            assertEquals(e.getCause().getMessage(), "Trying to create an object by extending an non-object base class");
        }

    }

    /**
     * Test the in-line RAML expressions
     * https://github.com/raml-org/raml-spec/blob/master/versions/raml-10/raml-10.md#type-expressions
     * <p>
     * - "ObjectA"             : Simple reference
     * - "ObjectA[]"           : Array of types
     * - "ObjectA[][]"         : Multi-dimensional array of types
     * - "string | ObjectA"    : Union of various types
     * - "(string | Person)[]" : An array of an in-line union of types
     */
    @Test
    public void createTypeString() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Type type;

        // It should properly resolve base types
        fType = parser.createType("string", scope);
        assertEquals(RAMLParser.BUILTIN_TYPES.get("string"), fType.get(1, TimeUnit.SECONDS));

        // It should properly resolve defined types
        fType = parser.createType("ObjectA", scope);
        assertEquals(ObjectA, fType.get(1, TimeUnit.SECONDS));

        // It should ignore whitespaces
        fType = parser.createType("  ObjectA     ", scope);
        assertEquals(ObjectA, fType.get(1, TimeUnit.SECONDS));

        // It should ignore simple parenthesis
        fType = parser.createType("(ObjectA)", scope);
        assertEquals(ObjectA, fType.get(1, TimeUnit.SECONDS));

        // It should properly resolve union types
        fType = parser.createType("ObjectA | ObjectB", scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(UnionType.class));
        assertThat(
            ((UnionType)type).unionTypes.stream().map(Type::getStructural).collect(Collectors.toList()),
            containsInAnyOrder(ObjectA, ObjectB)
        );

        // It should properly resolve array types
        fType = parser.createType("ObjectA[]", scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ArrayType.class));
        assertEquals(((ArrayType)type).itemType, ObjectA);

        // It should properly resolve multi-dimensional array types
        fType = parser.createType("ObjectA[][]", scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ArrayType.class));
        assertThat(((ArrayType)type).itemType, instanceOf(ArrayType.class));
        assertEquals(((ArrayType)((ArrayType)type).itemType).itemType, ObjectA);

        // It should properly resolve complex array of union types
        fType = parser.createType("(ObjectA | ObjectB)[]", scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ArrayType.class));
        assertThat(((ArrayType)type).itemType, instanceOf(UnionType.class));
        assertThat(
            ((UnionType)((ArrayType)type).itemType).unionTypes.stream().map(Type::getStructural).collect(Collectors.toList()),
            containsInAnyOrder(ObjectA, ObjectB)
        );
    }

    /**
     * Test if the same expressions like `createTypeString` can be evaluated
     * when using just the `type: XXX` facet in the type definition.
     */
    @Test
    public void createTypeMapExpressions() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Map<String, Object> yaml;
        Type type;

        // It should properly resolve base types
        yaml = parseYamlObject("type: string\n");
        fType = parser.createType(yaml, scope);
        assertEquals(RAMLParser.BUILTIN_TYPES.get("string"), fType.get(1, TimeUnit.SECONDS));

        // It should properly resolve defined types
        yaml = parseYamlObject("type: ObjectA\n");
        fType = parser.createType(yaml, scope);
        assertEquals(ObjectA, fType.get(1, TimeUnit.SECONDS));

        // It should ignore whitespaces
        yaml = parseYamlObject("type:   ObjectA     \n");
        fType = parser.createType(yaml, scope);
        assertEquals(ObjectA, fType.get(1, TimeUnit.SECONDS));

        // It should ignore simple parenthesis
        yaml = parseYamlObject("type: (ObjectA)\n");
        fType = parser.createType(yaml, scope);
        assertEquals(ObjectA, fType.get(1, TimeUnit.SECONDS));

        // It should properly resolve union types
        yaml = parseYamlObject("type: ObjectA | ObjectB\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(UnionType.class));
        assertThat(
            ((UnionType)type).unionTypes.stream().map(Type::getStructural).collect(Collectors.toList()),
            containsInAnyOrder(ObjectA, ObjectB)
        );

        // It should properly resolve array types
        yaml = parseYamlObject("type: ObjectA[]\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ArrayType.class));
        assertEquals(((ArrayType)type).itemType, ObjectA);

        // It should properly resolve multi-dimensional array types
        yaml = parseYamlObject("type: ObjectA[][]\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ArrayType.class));
        assertThat(((ArrayType)type).itemType, instanceOf(ArrayType.class));
        assertEquals(((ArrayType)((ArrayType)type).itemType).itemType, ObjectA);

        // It should properly resolve complex array of union types
        yaml = parseYamlObject("type: (ObjectA | ObjectB)[]\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ArrayType.class));
        assertThat(((ArrayType)type).itemType, instanceOf(UnionType.class));
        assertThat(
            ((UnionType)((ArrayType)type).itemType).unionTypes.stream().map(Type::getStructural).collect(Collectors.toList()),
            containsInAnyOrder(ObjectA, ObjectB)
        );
    }

    /**
     * Test if `createType` is correctly creating arrays
     * https://github.com/raml-org/raml-spec/blob/master/versions/raml-10/raml-10.md#array-type
     */
    @Test
    public void createTypeMapArrays() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Map<String, Object> yaml;
        AnnotativeType aType;
        StructuralType sType;
        Type type;

        // It should properly create arrays using `Type[]` format
        yaml = parseYamlObject("type: ObjectA[]\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ArrayType.class));
        assertEquals(((ArrayType)type).itemType, ObjectA);

        // It should properly create arrays using `type: array`
        yaml = parseYamlObject("type: array\nitems: ObjectA\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ArrayType.class));
        assertEquals(((ArrayType)type).itemType, ObjectA);

        // It should correctly annotate arrays with extra information
        yaml = parseYamlObject("type: array\nitems: ObjectA\n" +
                "uniqueItems: yes\nminItems: 123\nmaxItems: 456\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(AnnotativeType.class));

        sType = type.getStructural();
        assertThat(sType, instanceOf(ArrayType.class));
        assertEquals(((ArrayType)sType).itemType, ObjectA);

        aType = (AnnotativeType) type;
        assertEquals(aType.getUniqueItems(), true);
        assertEquals(aType.getMinItems(), (Integer)123);
        assertEquals(aType.getMaxItems(), (Integer)456);

        // It should correctly create an enum that carries the additional
        // annotative information when `enum` is present
        yaml = parseYamlObject("type: array\nitems: string\n" +
                "uniqueItems: yes\nminItems: 123\nmaxItems: 456\n" +
                "enum: [foo, bar]\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(AnnotativeType.class));

        sType = type.getStructural();
        assertThat(sType, instanceOf(EnumType.class));
        assertThat(((EnumType)sType).itemType, instanceOf(ArrayType.class));
        assertEquals(((ArrayType)((EnumType)sType).itemType).itemType, RAMLParser.BUILTIN_TYPES.get("string"));
        assertThat(((EnumType)sType).values, containsInAnyOrder("foo", "bar"));

    }

    /**
     * Test if `createType` is correctly creating objects
     * https://github.com/raml-org/raml-spec/blob/master/versions/raml-10/raml-10.md#object-type
     */
    @Test
    public void createTypeMapObjects() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Map<String, Object> yaml;
        AnnotativeType aType;
        StructuralType sType;
        Type type;

        // It should properly create objects using `type: object` but no properties
        yaml = parseYamlObject("type: object\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ObjectType.class));
        assertTrue(((ObjectType)type).properties.isEmpty());

        // It should properly imply objects if properties are defined
        yaml = parseYamlObject("properties:\n  foo: string\n  bar: string\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ObjectType.class));
        assertThat(((ObjectType)type).properties.keySet(), containsInAnyOrder("foo", "bar"));

        // It should properly create objects if both `type: object` and `properties` are defined
        yaml = parseYamlObject("type: object\nproperties:\n  foo: string\n  bar: string\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ObjectType.class));
        assertThat(((ObjectType)type).properties.keySet(), containsInAnyOrder("foo", "bar"));

        // It should properly create in-line types as properties
        yaml = parseYamlObject("type: object\nproperties:\n  foo: string\n  bar: string\n" +
                "  baz:\n    type: string\n    description: A bazzer\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ObjectType.class));
        assertThat(((ObjectType)type).properties.keySet(), containsInAnyOrder("foo", "bar", "baz"));
        assertThat(((ObjectType)type).properties.get("baz").getType(), instanceOf(AnnotativeType.class));
        assertEquals(((ObjectType)type).properties.get("baz").getType().getParent(), RAMLParser.BUILTIN_TYPES.get("string"));

        // It should properly handle optional and required
        yaml = parseYamlObject("type: object\nproperties:\n  foo: string\n  bar?: string\n" +
                "  faz:\n    type: string\n    required: yes\n" +
                "  baz:\n    type: string\n    required: no\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ObjectType.class));
        assertThat(((ObjectType)type).properties.keySet(), containsInAnyOrder("foo", "bar", "faz", "baz"));
        assertTrue(((ObjectType)type).properties.get("foo").required);
        assertFalse(((ObjectType)type).properties.get("bar").required);
        assertTrue(((ObjectType)type).properties.get("faz").required);
        assertFalse(((ObjectType)type).properties.get("baz").required);

        // It should correctly annotate objects with extra information
        yaml = parseYamlObject("type: object\nproperties:\n  foo: string\n  bar: string\n" +
                "minProperties: 123\nmaxProperties: 456\nadditionalProperties: no");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(AnnotativeType.class));

        sType = type.getStructural();
        assertThat(sType, instanceOf(ObjectType.class));
        assertThat(((ObjectType)sType).properties.keySet(), containsInAnyOrder("foo", "bar"));

        aType = (AnnotativeType) type;
        assertEquals(aType.getMinProperties(), (Integer)123);
        assertEquals(aType.getMaxProperties(), (Integer)456);
        assertEquals(aType.getAdditionalProperties(), false);

        // It should correctly create an enum that carries the additional
        // annotative information when `enum` is present
        yaml = parseYamlObject("type: object\nproperties:\n  foo: string\n  bar: string\n" +
                "minProperties: 123\nmaxProperties: 456\nadditionalProperties: no\n" +
                "enum:\n  - {\"foo\": \"1234\", \"bar\": \"456\"}\n" +
                "  -  {\"foo\": \"589\", \"bar\": \"012\"}\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(AnnotativeType.class));

        sType = type.getStructural();
        assertThat(sType, instanceOf(EnumType.class));
        assertThat(((EnumType)sType).itemType, instanceOf(ObjectType.class));

        sType = ((EnumType)sType).itemType.getStructural();
        assertThat(((ObjectType)sType).properties.keySet(), containsInAnyOrder("foo", "bar"));

        // (Note that the annotations are on the `enum`, That's the correct behaviour)
        aType = (AnnotativeType) type;
        assertEquals(aType.getMinProperties(), (Integer)123);
        assertEquals(aType.getMaxProperties(), (Integer)456);
        assertEquals(aType.getAdditionalProperties(), false);
    }


    /**
     * Test if `createType` is correctly creating variadic objects (maps)
     */
    @Test
    public void createTypeMapVariadicObjects() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Map<String, Object> yaml;
        Type type;

        // It should properly create variadic objects when the properties have regex keys
        yaml = parseYamlObject("type: object\nproperties:\n  /.*/: string\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(VariadicObjectType.class));
        assertEquals(((VariadicObjectType)type).variadicObjectType, RAMLParser.BUILTIN_TYPES.get("string"));

        // It should correctly detect the shared property type of all properties
        yaml = parseYamlObject("type: object\nproperties:\n  /.*/: integer\n" +
                "  /.[1-2]*/: integer\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(VariadicObjectType.class));
        assertEquals(((VariadicObjectType)type).variadicObjectType, RAMLParser.BUILTIN_TYPES.get("integer"));

        // It should fall-back to `any` if the property types are mixing
        yaml = parseYamlObject("type: object\nproperties:\n  /.*/: integer\n" +
                "  /.[1-2]*/: number\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(VariadicObjectType.class));
        assertEquals(((VariadicObjectType)type).variadicObjectType, RAMLParser.BUILTIN_TYPES.get("any"));

    }

    /**
     * Check if an array type can be properly extended
     * @throws Exception
     */
    @Test
    public void createTypeExtendArray() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Map<String, Object> yaml;
        Type type;

        // Extending an array should alter the structural type, but preserve the
        // annotative information
        yaml = parseYamlObject("type: AArrayA\nitems: ObjectB\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ArrayType.class));
        assertEquals(((ArrayType)type).itemType, ObjectB);
        assertEquals(type.getAnnotative().getDescription(), "An array of typeA");

        // Extending the annotative information should preserve the base values
        yaml = parseYamlObject("type: AArrayA\nexample: foobar\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(AnnotativeType.class));
        assertEquals(((ArrayType)type.getStructural()).itemType, ObjectA);
        assertEquals(type.getAnnotative().getDescription(), "An array of typeA");
        assertEquals(type.getAnnotative().getExample(), "foobar");

        // Extending an array with annotative information should correctly alter
        // the structure and preserve base annotative values
        yaml = parseYamlObject("type: AArrayA\nexample: foobar\nitems: ObjectB\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(AnnotativeType.class));
        assertEquals(((ArrayType)type.getStructural()).itemType, ObjectB);
        assertEquals(type.getAnnotative().getDescription(), "An array of typeA");
        assertEquals(type.getAnnotative().getExample(), "foobar");
    }

    /**
     * Check if an object type can be properly extended
     * @throws Exception
     */
    @Test
    public void createTypeExtendObject() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Map<String, Object> yaml;
        Type type;

        // Extending an object should append properties
        yaml = parseYamlObject("type: ObjectA\nproperties:\n  bar: string\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ObjectType.class));
        assertThat(((ObjectType)type).properties.keySet(), containsInAnyOrder("foo", "bar"));

        // (Check if "foo" is correctly marked as inherited from ObjectA)
        assertThat(
            ((ObjectType)type).properties.values().stream().map(o -> o.inheritedFrom).collect(Collectors.toList()),
            containsInAnyOrder(null, ObjectA)
        );

        // Extending an object with regex properties should create a variadic object
        yaml = parseYamlObject("type: ObjectA\nproperties:\n  /.*/: string\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(VariadicObjectType.class));
        assertEquals(((VariadicObjectType)type).variadicObjectType, RAMLParser.BUILTIN_TYPES.get("string"));

        // Extending an object with regex properties of variable types should create a variadic
        // object of `any` type.
        yaml = parseYamlObject("type: ObjectA\nproperties:\n  /.*/: integer\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(VariadicObjectType.class));
        assertEquals(((VariadicObjectType)type).variadicObjectType, RAMLParser.BUILTIN_TYPES.get("any"));

        // Extending a variadic object as a regular object does not change the variadic
        // type of the object.
        yaml = parseYamlObject("type: VarObjectA\nproperties:\n  bar: string\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(VariadicObjectType.class));
        assertEquals(((VariadicObjectType)type).variadicObjectType, RAMLParser.BUILTIN_TYPES.get("string"));

        // Extending a variadic object as a regular object, mixing the types does not change
        // the variadic type of the object, but does change the type to match the new properties
        yaml = parseYamlObject("type: VarObjectA\nproperties:\n  bar: integer\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(VariadicObjectType.class));
        assertEquals(((VariadicObjectType)type).variadicObjectType, RAMLParser.BUILTIN_TYPES.get("integer"));

        // Extending an object should append properties and handle additional annotations
        yaml = parseYamlObject("type: ObjectA\nproperties:\n  bar: string\nmaxProperties: 456\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(AnnotativeType.class));
        assertThat(type.getStructural(), instanceOf(ObjectType.class));
        assertThat(((ObjectType)type.getStructural()).properties.keySet(), containsInAnyOrder("foo", "bar"));
        assertEquals(type.getAnnotative().getDescription(), "An object value");
        assertEquals(type.getAnnotative().getMaxProperties(), (Integer)456);

        // Extending an object should perserve the initial annotative information
        yaml = parseYamlObject("type: AObjectA\nproperties:\n  bar: string\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type.getStructural(), instanceOf(ObjectType.class));
        assertThat(((ObjectType)type.getStructural()).properties.keySet(), containsInAnyOrder("foo", "bar"));
        assertEquals(type.getAnnotative().getDescription(), "An object of type ObjectA");

        // Extending the annotative information should preserve the base values
        yaml = parseYamlObject("type: AObjectA\nexample: foobar\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(AnnotativeType.class));
        assertThat(type.getStructural(), instanceOf(ObjectType.class));
        assertEquals(type.getAnnotative().getDescription(), "An object of type ObjectA");
        assertEquals(type.getAnnotative().getExample(), "foobar");

        // Extending an object both with structural and annotative information should
        // properly create a new structure, and carry along the base annotative values
        yaml = parseYamlObject("type: AObjectA\nproperties:\n  bar: string\nmaxProperties: 456\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(AnnotativeType.class));
        assertThat(type.getStructural(), instanceOf(ObjectType.class));
        assertThat(((ObjectType)type.getStructural()).properties.keySet(), containsInAnyOrder("foo", "bar"));
        assertEquals(type.getAnnotative().getDescription(), "An object of type ObjectA");
        assertEquals(type.getAnnotative().getMaxProperties(), (Integer)456);

    }

    /**
     * Check if an enumeration can be properly extended
     * @throws Exception
     */
    @Test
    public void createTypeExtendEnum() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Map<String, Object> yaml;
        Type type;

        // Extending an enum should append values
        yaml = parseYamlObject("type: EnumA\nenum:\n  - baz\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type.getStructural(), instanceOf(EnumType.class));
        assertThat(((EnumType)type).values, containsInAnyOrder("foo", "bar", "baz"));

        // Extending an enum should perserve the annotation values
        yaml = parseYamlObject("type: AEnumA\nenum:\n  - baz\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type.getStructural(), instanceOf(EnumType.class));
        assertThat(((EnumType)type.getStructural()).values, containsInAnyOrder("foo", "bar", "baz"));
        assertEquals(type.getAnnotative().getDescription(), "An enumeration of strings");
    }

    /**
     * Test if unions properly register relations
     * @throws Exception
     */
    @Test
    public void testUnionRelation() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Type type;

        // Create a union of two known types
        fType = parser.createType("ObjectA | ObjectB", scope);
        type = fType.get(1, TimeUnit.SECONDS);

        // The type should be a union
        assertThat(type, instanceOf(UnionType.class));

        // The relations should be established
        assertThat(
            scope.getRelationsOfKind(ObjectA, TypeScopeRelation.Relation.UnionOf)
                .map(r -> r.target).collect(Collectors.toList()),
            containsInAnyOrder(type)
        );
        assertThat(
            scope.getRelationsOfKind(ObjectB, TypeScopeRelation.Relation.UnionOf)
                    .map(r -> r.target).collect(Collectors.toList()),
            containsInAnyOrder(type)
        );

    }

    /**
     * Test if inheritance properly register relations
     * @throws Exception
     */
    @Test
    public void testInheritanceRelation() throws Exception {
        RAMLParser parser = new RAMLParser();
        TypeScope scope = generateTestScope();
        CompletableFuture<Type> fType;
        Map<String, Object> yaml;
        Type type;

        // Create a union of two known types
        yaml = parseYamlObject("type: ObjectA\nproperties:\n  foo: string\n");
        fType = parser.createType(yaml, scope);
        type = fType.get(1, TimeUnit.SECONDS);
        assertThat(type, instanceOf(ObjectType.class));

        // The relations should be established
        assertThat(
            scope.getRelationsOfKind(ObjectA, TypeScopeRelation.Relation.ParentOf)
                    .map(r -> r.target).collect(Collectors.toList()),
            containsInAnyOrder(type)
        );
        assertThat(
            scope.getRelationsOfKind(type, TypeScopeRelation.Relation.ChildOf)
                    .map(r -> r.target).collect(Collectors.toList()),
            containsInAnyOrder(ObjectA)
        );
    }

}