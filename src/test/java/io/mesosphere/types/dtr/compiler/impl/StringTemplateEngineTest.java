package io.mesosphere.types.dtr.compiler.impl;

import io.mesosphere.types.dtr.compiler.internal.CompiledFragment;
import io.mesosphere.types.dtr.compiler.internal.CompiledFragmentType;
import io.mesosphere.types.dtr.compiler.internal.arguments.FileObjectTypeFragmentArguments;
import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.Value;
import io.mesosphere.types.dtr.models.internal.scopes.GlobalScope;
import io.mesosphere.types.dtr.models.internal.types.*;
import io.mesosphere.types.dtr.models.internal.values.*;
import io.mesosphere.types.dtr.repository.impl.LocalRepository;
import org.junit.Test;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.instanceOf;

public class StringTemplateEngineTest {

    // Values
    private static final Value VALUE_ANY = new AnyValue();
    private static final Value VALUE_BINARY = new BinaryValue(0, 1024, new ArrayList<>());
    private static final Value VALUE_BOOLEAN = new BooleanValue();
    private static final Value VALUE_VOID = new VoidValue();
    private static final Value VALUE_DATETIME_ONLY = new DateTimeValue(true, true, false);
    private static final Value VALUE_DATETIME = new DateTimeValue(true, true, true);
    private static final Value VALUE_DATE_ONLY = new DateTimeValue(true, false, false);
    private static final Value VALUE_TIME_ONLY = new DateTimeValue(false, true, false);
    private static final Value VALUE_STRING = new StringValue();
    private static final Value VALUE_NUMBER = new NumberValue(false);
    private static final Value VALUE_INTEGER = new NumberValue(true);

    // Types
    private static final ScalarType TYPE_ANY = new ScalarType((Type) null, "any", VALUE_ANY);
    private static final ScalarType TYPE_BINARY = new ScalarType((Type) null, "binary", VALUE_BINARY);
    private static final ScalarType TYPE_BOOLEAN = new ScalarType((Type) null, "boolean", VALUE_BOOLEAN);
    private static final ScalarType TYPE_VOID = new ScalarType((Type) null, "void", VALUE_VOID);
    private static final ScalarType TYPE_DATETIME_ONLY = new ScalarType((Type) null, "datetime_only", VALUE_DATETIME_ONLY);
    private static final ScalarType TYPE_DATETIME = new ScalarType((Type) null, "datetime", VALUE_DATETIME);
    private static final ScalarType TYPE_DATE_ONLY = new ScalarType((Type) null, "date_only", VALUE_DATE_ONLY);
    private static final ScalarType TYPE_TIME_ONLY = new ScalarType((Type) null, "time_only", VALUE_TIME_ONLY);
    private static final ScalarType TYPE_STRING = new ScalarType((Type) null, "string", VALUE_STRING);
    private static final ScalarType TYPE_NUMBER = new ScalarType((Type) null, "number", VALUE_NUMBER);
    private static final ScalarType TYPE_INTEGER = new ScalarType((Type) null, "integer", VALUE_INTEGER);

    /**
     * Get an instance to the StringTemplate engine initialized in the mock repository
     *
     * @return
     * @throws IOException
     */
    StringTemplateEngine instanceEngine() throws IOException {
        LocalRepository mockRepo = new LocalRepository("src/test/java/files/testrepo");
        return new StringTemplateEngine(mockRepo.openProjectCompiler("debug", "1"));
    }

    /**
     * Check if the storage types are correctly resolved to the language-specific
     * types using the `config/language.ini` from the repository.
     *
     * @throws Exception
     */
    @Test
    public void testGetStorageTypeName() throws Exception {
        StringTemplateEngine ste = instanceEngine();

        assertEquals(
                "Its_any",
                ste.getStorageTypeName(VALUE_ANY)
        );
        assertEquals(
                "Its_binary",
                ste.getStorageTypeName(VALUE_BINARY)
        );
        assertEquals(
                "Its_boolean",
                ste.getStorageTypeName(VALUE_BOOLEAN)
        );
        assertEquals(
                "Its_void",
                ste.getStorageTypeName(VALUE_VOID)
        );
        assertEquals(
                "Its_datetime_only",
                ste.getStorageTypeName(VALUE_DATETIME_ONLY)
        );
        assertEquals(
                "Its_date_only",
                ste.getStorageTypeName(VALUE_DATE_ONLY)
        );
        assertEquals(
                "Its_time_only",
                ste.getStorageTypeName(VALUE_TIME_ONLY)
        );
        assertEquals(
                "Its_datetime",
                ste.getStorageTypeName(VALUE_DATETIME)
        );
        assertEquals(
                "Its_string",
                ste.getStorageTypeName(VALUE_STRING)
        );
        assertEquals(
                "Its_number",
                ste.getStorageTypeName(VALUE_NUMBER)
        );
        assertEquals(
                "Its_integer",
                ste.getStorageTypeName(VALUE_INTEGER)
        );
    }

    /**
     * Test the evaluation expressions of all scalar types
     *
     * @throws Exception
     */
    @Test
    public void testGetValueExpression_ScalarValue() throws Exception {
        StringTemplateEngine ste = instanceEngine();
        GlobalScope scope = new GlobalScope();

        // Check the invocation of `valueOf` template with scalars
        assertEquals(
                "any=I'manobject!",
                ste.getValueExpression(new Object() {
                    @Override
                    public String toString() {
                        return "I'manobject!";
                    }
                }, TYPE_ANY, scope)
        );
        assertEquals(
                "binary=72,101,108,108,111",
                ste.getValueExpression("Hello".getBytes("UTF-8"), TYPE_BINARY, scope)
        );
        assertEquals(
                "boolean=false",
                ste.getValueExpression(false, TYPE_BOOLEAN, scope)
        );
        assertEquals(
                "boolean=true",
                ste.getValueExpression(true, TYPE_BOOLEAN, scope)
        );
        assertEquals(
                "void=null",
                ste.getValueExpression("ignored", TYPE_VOID, scope)
        );
        assertEquals(
                "string=foobar",
                ste.getValueExpression("foobar", TYPE_STRING, scope)
        );
        assertEquals(
                "number=123.456",
                ste.getValueExpression(123.456, TYPE_NUMBER, scope)
        );
        assertEquals(
                "integer=789",
                ste.getValueExpression(789, TYPE_INTEGER, scope)
        );

        // Create a date format to parse the test dates
        DateTimeFormatter sdf = DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneId.of("Europe/Berlin"));

        assertEquals(
                "datetime_only=dto: Sat, 3 Dec 2011 10:15:30 AM CET",
                ste.getValueExpression(sdf.parse("2011-12-03T10:15:30+01:00[Europe/Berlin]"), TYPE_DATETIME_ONLY, scope)
        );
        assertEquals(
                "date_only=do: Sat, 3 Dec 2011 10:15:30 AM CET",
                ste.getValueExpression(sdf.parse("2011-12-03T10:15:30+01:00[Europe/Berlin]"), TYPE_DATE_ONLY, scope)
        );
        assertEquals(
                "time_only=to: Sat, 3 Dec 2011 10:15:30 AM CET",
                ste.getValueExpression(sdf.parse("2011-12-03T10:15:30+01:00[Europe/Berlin]"), TYPE_TIME_ONLY, scope)
        );
        assertEquals(
                "datetime=dt: Sat, 3 Dec 2011 10:15:30 AM CET",
                ste.getValueExpression(sdf.parse("2011-12-03T10:15:30+01:00[Europe/Berlin]"), TYPE_DATETIME, scope)
        );
        assertEquals(
                "datetime=dt: Sat, 3 Dec 2011 10:15:30 AM CET",
                ste.getValueExpression(sdf.parse("2011-12-03T10:15:30+01:00[Europe/Berlin]"), TYPE_DATETIME, scope)
        );
    }

    /**
     * Test the "missing value" evaluation of all scalar types
     * @throws Exception
     */
    @Test
    public void testGetValueExpression_ScalarNull() throws Exception {
        StringTemplateEngine ste = instanceEngine();
        GlobalScope scope = new GlobalScope();

        // Check the invocation of `valueOf` template with null values
        assertEquals(
            "null_any=null",
            ste.getValueExpression(null, TYPE_ANY, scope)
        );
        assertEquals(
            "null_binary=null",
            ste.getValueExpression(null, TYPE_BINARY, scope)
        );
        assertEquals(
            "null_boolean=null",
            ste.getValueExpression(null, TYPE_BOOLEAN, scope)
        );
        assertEquals(
            "null_void=null",
            ste.getValueExpression(null, TYPE_VOID, scope)
        );
        assertEquals(
            "null_string=null",
            ste.getValueExpression(null, TYPE_STRING, scope)
        );
        assertEquals(
            "null_number=null",
            ste.getValueExpression(null, TYPE_NUMBER, scope)
        );
        assertEquals(
            "null_integer=null",
            ste.getValueExpression(null, TYPE_INTEGER, scope)
        );
        assertEquals(
            "null_datetime_only=null",
            ste.getValueExpression(null, TYPE_DATETIME_ONLY, scope)
        );
        assertEquals(
            "null_date_only=null",
            ste.getValueExpression(null, TYPE_DATE_ONLY, scope)
        );
        assertEquals(
            "null_time_only=null",
            ste.getValueExpression(null, TYPE_TIME_ONLY, scope)
        );
        assertEquals(
            "null_datetime=null",
            ste.getValueExpression(null, TYPE_DATETIME, scope)
        );
    }

    /**
     * Test the instance evaluation, passing various expressions
     * TODO: Make the test order-agnostic for the object properties and list items
     *
     * @throws Exception
     */
    @Test
    public void testGetValueExpression_Structural() throws Exception {
        StringTemplateEngine ste = instanceEngine();
        GlobalScope scope = new GlobalScope();

        //
        // Check the invocation of `inst` template with arrays
        //
        // Note that the engine should correctly cast the values passed using the
        // type from the array item.
        //
        // NOTE: The `string=XXX` comes from the valueOf evaluation of the scalar contents
        //
        ArrayType A1 = new ArrayType(null, "A1", TYPE_STRING);
        assertEquals(
            "[string=foo, string=bar]",
            ste.getValueExpression(
                new ArrayList<String>() {{
                    add("foo"); add("bar");
                }},
                A1,
                scope
            )
        );
        ArrayType A2 = new ArrayType(null, "A2", TYPE_INTEGER);
        assertEquals(
            "[integer=123, null_integer=null]",
            ste.getValueExpression(
                new ArrayList<Integer>() {{
                    add(123); add(null);
                }},
                A2,
                scope
            )
        );

        // Nested arrays
        ArrayType A3 = new ArrayType(null, "A3", A2);
        assertEquals(
            "[[integer=123], [integer=234, integer=345]]",
            ste.getValueExpression(
                new ArrayList<ArrayList<Integer>>() {{
                    add(new ArrayList<Integer>() {{
                        add(123);
                    }});
                    add(new ArrayList<Integer>() {{
                        add(234);
                        add(345);
                    }});
                }},
                A3,
                scope
            )
        );


        //
        // Check the invocation of `inst` template with objects of known type
        //
        // Like with the array above, the engine should cast the types of each property
        // using the correct evaluations
        //
        ObjectType O1 = new ObjectType((Type)null, "O1", new HashMap<String, ObjectPropertyType>() {{
            put("foo", new ObjectPropertyType(TYPE_STRING, "foo", false, null));
            put("bar", new ObjectPropertyType(TYPE_INTEGER, "bar", false, null));
        }});
        assertEquals(
            "O[O1]{bar:integer=1234, foo:string=foo_value}",
            ste.getValueExpression(
                new HashMap<String, Object>() {{
                    put("foo", "foo_value");
                    put("bar", 1234);
                }},
                O1
                , scope
            )
        );

        // Nested objects
        ObjectType O2 = new ObjectType((Type)null, "O2", new HashMap<String, ObjectPropertyType>() {{
            put("baz", new ObjectPropertyType(O1, "baz", false, null));
            put("qux", new ObjectPropertyType(TYPE_BOOLEAN, "qux", false, null));
        }});
        assertEquals(
            "O[O2]{qux:boolean=false, baz:O[O1]{bar:integer=1234, foo:string=foo_value}}",
            ste.getValueExpression(
                new HashMap<String, Object>() {{
                    put("baz", new HashMap<String, Object>() {{
                        put("foo", "foo_value");
                        put("bar", 1234);
                    }});
                    put("qux", false);
                }},
                O2,
                scope
            )
        );

        //
        // Check the invocation of `inst` template with variadic objects
        //
        // In this example all property types are of type "String", therefore the common type
        // will be string.
        //
        VariadicObjectType O3 = new VariadicObjectType((Type)null, "O2", new HashMap<String, ObjectPropertyType>() {{
            put("/.*/", new ObjectPropertyType(TYPE_STRING, "foo", false, null));
            put("bar", new ObjectPropertyType(TYPE_STRING, "bar", false, null));
        }});
        assertEquals(
            "{{bar:string=bar_value, foo:string=foo_value}}",
            ste.getValueExpression(
                new HashMap<String, Object>() {{
                    put("foo", "foo_value");
                    put("bar", "bar_value");
                }},
                O3
                , scope
            )
        );

        //
        // In this example we are mixing the types, so "any" value will be used.
        //
        // However, note that since it's impossible to write an "any" serializer, the engine
        // should pick the correct value for each argument.
        //
        VariadicObjectType O4 = new VariadicObjectType((Type)null, "O2", new HashMap<String, ObjectPropertyType>() {{
            put("/.*/", new ObjectPropertyType(TYPE_STRING, "foo", false, null));
            put("bar", new ObjectPropertyType(TYPE_INTEGER, "bar", false, null));
        }});
        assertThat(O4.variadicObjectType.getStructural(), instanceOf(ScalarType.class));
        assertThat(((ScalarType)O4.variadicObjectType.getStructural()).storageType, instanceOf(AnyValue.class));
        assertEquals(
            "{{bar:string=bar_value, foo:string=foo_value, baz:integer=1234}}",
            ste.getValueExpression(
                new HashMap<String, Object>() {{
                    put("foo", "foo_value");
                    put("bar", "bar_value");
                    put("baz", 1234);
                }},
                O4
                , scope
            )
        );

        //
        // Check the invocation of `inst` template with union of objects
        //
        ObjectType O5 = (new ObjectType((Type)null, "O5", new HashMap<String, ObjectPropertyType>() {{
            put("foo", new ObjectPropertyType(TYPE_STRING, "foo", false, null));
            put("kind", new ObjectPropertyType(TYPE_STRING, "kind", false, null));
        }})).setDiscriminatorProperty("kind");
        ObjectType O6 = (new ObjectType((Type)null, "O6", new HashMap<String, ObjectPropertyType>() {{
            put("bar", new ObjectPropertyType(TYPE_STRING, "bar", false, null));
            put("kind", new ObjectPropertyType(TYPE_STRING, "kind", false, null));
        }})).setDiscriminatorProperty("kind");
        UnionType O7 = new UnionType(null, "O7", new ArrayList<Type>() {{
            add(O5); add(O6);
        }});

        // The discriminator property "kind" defines the type, so if it's O5, an
        // instance of O5 should be used:
        assertEquals(
            "U[O7](O[O5]{kind:string=O5, foo:string=foo_value})",
            ste.getValueExpression(
                new HashMap<String, Object>() {{
                    put("foo", "foo_value");
                    put("kind", "O5");
                }},
                O7
                , scope
            )
        );

        // Likewise, when "kind" is "O6", an instance of O6 should be used
        assertEquals(
            "U[O7](O[O6]{bar:string=bar_value, kind:string=O6})",
            ste.getValueExpression(
                new HashMap<String, Object>() {{
                    put("bar", "bar_value");
                    put("kind", "O6");
                }},
                O7
                , scope
            )
        );
    }

    /**
     * Test if fetching a global fragment and rendering some contents works
     * @throws Exception
     */
    @Test
    public void getGlobalFragment() throws Exception {
        StringTemplateEngine ste = instanceEngine();
        GlobalScope scope = new GlobalScope();

        // Create a type that we are going to write in the file
        ObjectType T1 = new ObjectType((Type)null, "T1", new HashMap<String, ObjectPropertyType>() {{
            put("foo", new ObjectPropertyType(TYPE_STRING, "foo", false, null));
            put("bar", new ObjectPropertyType(TYPE_INTEGER, "bar", false, null));
        }});
        scope.define("T1", T1);

        // Get an instance to the FILE_CONTENTS compiled fragment,
        // that effectively is the `contents(type)` template.
        CompiledFragment frag = ste.getCompiledFragment(CompiledFragmentType.FILE_CONTENTS);

        // Create the appropriate arguments
        FileObjectTypeFragmentArguments args = new FileObjectTypeFragmentArguments(T1, scope, ste);

        // Check if the contents are correct
        assertEquals(
            "Definition of T1",
            frag.render(args)
        );
    }

    @Test
    public void sanitizeExpression() throws Exception {
    }

}