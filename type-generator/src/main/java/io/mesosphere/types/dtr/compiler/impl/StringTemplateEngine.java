package io.mesosphere.types.dtr.compiler.impl;

import io.mesosphere.types.dtr.compiler.internal.*;
import io.mesosphere.types.dtr.compiler.internal.arguments.PrecalcValueArguments;
import io.mesosphere.types.dtr.compiler.internal.arguments.TypeArguments;
import io.mesosphere.types.dtr.compiler.internal.arguments.ValueArguments;
import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeScope;
import io.mesosphere.types.dtr.models.internal.Value;
import io.mesosphere.types.dtr.models.internal.types.*;
import io.mesosphere.types.dtr.models.internal.values.AnyValue;
import io.mesosphere.types.dtr.models.internal.values.DateTimeValue;
import io.mesosphere.types.dtr.repository.internal.RepositoryView;
import org.antlr.runtime.Token;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.ini4j.Ini;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupString;
import org.stringtemplate.v4.StringRenderer;
import org.stringtemplate.v4.misc.Misc;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A StringTemplateEngine engine is using the StringTemplateEngine template engine to
 * compile a user expression into a buffer.
 */
public class StringTemplateEngine implements CompilerEngine {

    /**
     * The zone ID to use when parsing/writing dates
     */
    private ZoneId dateZone;

    /**
     * Mapping of scalar types
     */
    private Map<String, String> scalarTypes;

    /**
     * Mapping of scalar types
     */
    private Map<String, DateTimeFormatter> dateParsers;
    private Map<String, DateTimeFormatter> dateExpressions;

    /**
     * The base template group
     */
    private STGroup group;

    /**
     * Create a new string template engine using the
     *
     * @param compilerView
     */
    public StringTemplateEngine(RepositoryView compilerView) throws IOException {
        // Load the root template group that should contain
        // all the child templates
        this.group = new STGroupRepository(compilerView, "compiler.stg", '<', '>');

        // Register <escape()> formatter
        this.group.registerRenderer(String.class, new FormattersRenderer());
        this.group.defineTemplate("escape", "value", "<value; format=\"_escapeFormatter\">");
        this.group.defineTemplate("capitalize", "value", "<value; format=\"_capitalizeFormatter\">");

        // Load the language settings
        Ini ini = new Ini();
        ini.load(compilerView.openFile("config/language.ini"));
        String arrayTemplate = ini.get("structures", "array");

        // Load scalar types
        this.scalarTypes = ini.get("scalar_types");

        // Locate date expression formatters
        Map<String, String> dateSection = ini.get("date");
        if (dateSection == null) dateSection = new HashMap<>();
        this.dateParsers = new HashMap<>();
        this.dateExpressions = new HashMap<>();
        this.dateZone = ZoneId.of(dateSection.getOrDefault("timezone", "Europe/Berlin"));

        // Prepare defaults
        dateParsers.put("date_only", DateTimeFormatter.ISO_LOCAL_DATE);
        dateExpressions.put("date_only", DateTimeFormatter.ISO_LOCAL_DATE);
        dateParsers.put("time_only", DateTimeFormatter.ISO_LOCAL_TIME);
        dateExpressions.put("time_only", DateTimeFormatter.ISO_LOCAL_TIME);
        dateParsers.put("datetime_only", DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        dateExpressions.put("datetime_only", DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        dateParsers.put("datetime", DateTimeFormatter.ISO_DATE_TIME);
        dateExpressions.put("datetime", DateTimeFormatter.ISO_DATE_TIME);

        // Parse user options
        dateSection.forEach((key, value) -> {
            if (key.endsWith("_expr") || key.endsWith("_parse")) {
                Map<String, DateTimeFormatter> target;
                if (key.endsWith("_expr")) {
                    target = dateExpressions;
                    key = key.substring(0, key.length() - 5);
                }
                else {
                    target = dateParsers;
                    key = key.substring(0, key.length() - 6);
                }

                switch (value) {
                    case "BASIC_ISO_DATE":
                        target.put(key, DateTimeFormatter.BASIC_ISO_DATE.withZone(this.dateZone));
                        break;
                    case "ISO_LOCAL_DATE":
                        target.put(key, DateTimeFormatter.ISO_LOCAL_DATE.withZone(this.dateZone));
                        break;
                    case "ISO_OFFSET_DATE":
                        target.put(key, DateTimeFormatter.ISO_OFFSET_DATE.withZone(this.dateZone));
                        break;
                    case "ISO_DATE":
                        target.put(key, DateTimeFormatter.ISO_DATE.withZone(this.dateZone));
                        break;
                    case "ISO_LOCAL_TIME":
                        target.put(key, DateTimeFormatter.ISO_LOCAL_TIME.withZone(this.dateZone));
                        break;
                    case "ISO_OFFSET_TIME":
                        target.put(key, DateTimeFormatter.ISO_OFFSET_TIME.withZone(this.dateZone));
                        break;
                    case "ISO_TIME":
                        target.put(key, DateTimeFormatter.ISO_TIME.withZone(this.dateZone));
                        break;
                    case "ISO_LOCAL_DATE_TIME":
                        target.put(key, DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(this.dateZone));
                        break;
                    case "ISO_OFFSET_DATE_TIME":
                        target.put(key, DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(this.dateZone));
                        break;
                    case "ISO_ZONED_DATE_TIME":
                        target.put(key, DateTimeFormatter.ISO_ZONED_DATE_TIME.withZone(this.dateZone));
                        break;
                    case "ISO_DATE_TIME":
                        target.put(key, DateTimeFormatter.ISO_DATE_TIME.withZone(this.dateZone));
                        break;
                    case "ISO_ORDINAL_DATE":
                        target.put(key, DateTimeFormatter.ISO_ORDINAL_DATE.withZone(this.dateZone));
                        break;
                    case "ISO_WEEK_DATE":
                        target.put(key, DateTimeFormatter.ISO_WEEK_DATE.withZone(this.dateZone));
                        break;
                    case "ISO_INSTANT":
                        target.put(key, DateTimeFormatter.ISO_INSTANT.withZone(this.dateZone));
                        break;
                    case "RFC_1123_DATE_TIME":
                        target.put(key, DateTimeFormatter.RFC_1123_DATE_TIME.withZone(this.dateZone));
                        break;
                    default:
                        target.put(key, DateTimeFormatter.ofPattern(value).withZone(this.dateZone));
                        break;
                }
            }
        });
    }

    /**
     * Evaluate the provided template to the given output, using the values for the macros
     * from the BlockArguments provided
     *
     * @param templateName The name of the template to render
     * @param output       Where to store the generated output
     * @param arguments    The arguments to the template
     */
    private void templateWrite(OutputStream output, String templateName, BlockArguments arguments) throws IOException {
        // Write template to the output stream
        byte[] buffer = templateRender(templateName, arguments).getBytes("UTF-8");
        output.write(buffer);
    }

    /**
     * Evaluate the provided template to the given output, using the values for the macros
     * from the BlockArguments provided
     *
     * @param templateName The name of the template to render
     * @param arguments    The arguments to the template
     * @return The contents of the template
     */
    private String templateRender(String templateName, BlockArguments arguments) throws IOException {
        ST tpl = group.getInstanceOf(templateName);
        if (tpl == null) {
            throw new IOException("Unable to locate template " + templateName);
        }

        // Import public getter methods as fields from BlockArguments
        for (Method method : arguments.getClass().getDeclaredMethods()) {
            try {
                if (method.getName().startsWith("get")) {
                    String fieldName = method.getName().substring(3);
                    fieldName = fieldName.substring(0, 1).toLowerCase() + fieldName.substring(1);
                    tpl.add(fieldName, method.invoke(arguments));
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }
        }

        // Import public properties as fields from BlockArguments
        for (Field field : arguments.getClass().getFields()) {
            try {
                tpl.add(field.getName(), field.get(arguments));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        // Write template to the output stream
        return tpl.render();
    }

//    /**
//     * Use the project configuration to get the names for the internal types
//     * (For example internal "string" might be "char *" in C++)
//     *
//     * @param type The internal type for which to get the type name
//     * @return The name of the type as a plain string
//     */
//    @Override
//    public String getTypeName(Type type) {
//        // Get mapping of scalar types
//        if (type instanceof ScalarType) {
//            Value storageType = ((ScalarType) type).storageType;
//            String storageName = storageType.toName();
//
//            // Lookup that value from the language-specific scalar table
//            return scalarTypes.getOrDefault(storageName, storageName);
//        }
//
//        // Otherwise use the type identifier
//        return type.getId();
//    }

    /**
     * Return the user-defined storage type for the specified value
     *
     * @param storageType The internal storage type for which to get a name
     * @return Returns an expression with the type of the given storage type on the language
     */
    @Override
    public String getStorageTypeName(Value storageType) {
        String storageName = storageType.toName();
        return scalarTypes.getOrDefault(storageName, storageName);
    }

    /**
     * Render a value template
     *
     * @param template
     * @param type
     * @param kind
     * @param value
     * @return
     */
    private String renderValueTemplate(String template, Type type, TypeScope scope, String kind, Object value) {
        // Try the template specified
        ST tpl = group.getInstanceOf(template);

        // Fallback to "valueof" if the template is missing
        if (tpl == null) {
            System.err.println("WARNING: Could not find template `" + template + "`, falling back to `toString`");
            tpl = group.getInstanceOf("valueof");
        }

        // if the "valueof" template is not defined, use the default toString evaluation
        if (tpl == null) {
            return value.toString();
        }

        // Define properties
        try {
            tpl.add("type", TypeArguments.forType(type, scope,this));
        } catch (Exception e) { }
        try {
            tpl.add("kind", kind);
        } catch (Exception e) { }
        try {

            // If given a pre-calculated value arguments, just store it
            if (value instanceof ValueArguments) {
                tpl.add("val", value);
            }

            // If given a map of objects, create a ValueArgument for every key
            else if (value instanceof Map) {
                tpl.add("val", (Map<String, PrecalcValueArguments>)((Map<String, Object>) value).entrySet().stream()
                    .collect(Collectors.toMap(
                      kv -> (String)kv.getKey(),
                      kv -> new PrecalcValueArguments(kv.getValue(), type, scope,kv.getValue() == null, (kv.getValue() == null) ? "" : kv.getValue().toString())
                    ))
                );
            }

            // If given a list of objects, create a ValueArgument for every item
            else if (value instanceof List) {
                tpl.add("val", ((List<Object>) value).stream()
                    .map(o -> new PrecalcValueArguments(o, type, scope,o == null, (o == null) ? "" : o.toString()))
                    .collect(Collectors.toList())
                );
            }

            // If given a raw object, try to stringify using the type information
            // we have available, both from java reflection and `type` instance.
            else {

                // Perform fine-grained transformations depending on the
                // type configuration we were given
                String exprValue;
                if (value == null) {
                    exprValue = "";
                } else {
                    exprValue = value.toString();
                    if (type instanceof ScalarType) {
                        Value storageType = ((ScalarType) type).storageType;

                        //
                        // Date Objects
                        //
                        if (storageType instanceof DateTimeValue) {
                            // If given a string, first parse it using the `<type>_parse` expression
                            // from the [date] ini section and then convert it to the expression
                            // described in `<type>_expr`.
                            if (value instanceof String) {
                                TemporalAccessor t = dateParsers.get(storageType.toName()).parse(value.toString());
                                exprValue = dateParsers.get(storageType.toName()).format(t);
                            }
                            // If given a timezone-unaware date, use the timezone from the
                            // [date] section of the ini
                            else if (value instanceof Date) {
                                TemporalAccessor t = ((Date)value).toInstant().atZone(dateZone);
                                exprValue = dateParsers.get(storageType.toName()).format(t);
                            }

                            // If given a timezone-aware date, just format using the `<type>_expr`
                            // expression from the ini file
                            else if (value instanceof TemporalAccessor) {
                                exprValue = dateParsers.get(storageType.toName()).format((TemporalAccessor) value);
                            }
                        }
                    }
                }

                // Add a pre-calculated value argument with the type and value given
                tpl.add("val", new PrecalcValueArguments(
                    value,
                    type,
                    scope,
                    value == null,
                    exprValue)
                );
            }
        } catch (Exception e) {
            // An exception will be thrown if this argument is not defined
            // in the template. We just ignore such errors...
        }

        // Render
        return tpl.render();
    }

    /**
     * Return the user-defined value for the specified type
     *
     * @param value The value to get an expression for
     * @return
     * @throws IllegalArgumentException
     */
    @Override
    public String getValueExpression(Object value, Type valueType, TypeScope scope) throws IllegalArgumentException {
        StructuralType type = valueType.getStructural();

        if (type instanceof ArrayType) {
            if (!(value instanceof List)) {
                return renderValueTemplate("inst", type, scope,"array", null);
            }

            // Collect the value expressions
            List<String> values = ((List<Object>) value).stream()
                    .map(o -> getValueExpression(o, ((ArrayType) type).itemType, scope))
                    .collect(Collectors.toList());

            // Render template
            return renderValueTemplate(
                    "inst",
                    type,
                    scope,
                    "array",
                    values
            );

        } else if (type instanceof VariadicObjectType) {
            if (!(value instanceof Map)) {
                return renderValueTemplate("inst", type, scope,"variadic_object", null);
            }

            // Create a map with the object keys as given and the values
            // composed as scalars with guessed type
            Map<String, String> values = ((Map<String, Object>) value).entrySet().stream()
                    .collect(Collectors.toMap(
                            o -> o.getKey(),
                            o -> {
                                Type useType = ((VariadicObjectType) type).variadicObjectType;

                                // If the storage type is "Any", try to guess a concrete type for the instance, since
                                // there is no way to write a template fragment that represents "any" value.
                                if (useType.getStructural() instanceof ScalarType) {
                                    if (((ScalarType) useType.getStructural()).storageType instanceof AnyValue) {
                                        useType = ScalarType.guessByValue(o.getValue());
                                    }
                                }

                                // Use the type above to render the value for this variable
                                return getValueExpression(o.getValue(), useType, scope);
                            }
                    ));

            // Render template
            return renderValueTemplate(
                    "inst",
                    type,
                    scope,
                    "variadic_object",
                    values
            );

        } else if (type instanceof ObjectType) {
            if (!(value instanceof Map)) {
                return renderValueTemplate(
                        "inst",
                        type,
                        scope,
                        "object",
                        null
                );
            }

            // Create a map with the object keys and value expressions
            Map<String, ObjectPropertyType> props = ((ObjectType) type).properties;
            Map<String, String> values = ((Map<String, Object>) value).entrySet().stream()
                    .collect(Collectors.toMap(
                            o -> o.getKey(),
                            o -> {
                                try {
                                    return getValueExpression(o.getValue(), props.get(o.getKey()).getType(), scope);
                                } catch (Exception e) {
                                    throw new IllegalArgumentException("Using a property type that is not resolved yet");
                                }
                            }
                    ));

            // Render template
            return renderValueTemplate("inst", type, scope,"object", values);

        } else if (type instanceof EnumType) {

            // Render template
            return renderValueTemplate("inst", type, scope,"enum", value);

        } else if (type instanceof UnionType) {
            if (!(value instanceof Map)) {
                return renderValueTemplate("inst", type, scope,"union", null);
            }

            // Try to figure out on which child type does this object belong to
            Type matchedType = null;
            for (Type t : ((UnionType) type).unionTypes) {
                if (t instanceof ObjectType) {
                    // Find out the value of the discriminator property and how it
                    // compares to the actual value in the given map
                    String discriminatorProperty = ((ObjectType) t).discriminatorProperty;
                    String discriminatorValue = (String) ((Map) value).getOrDefault(discriminatorProperty, null);
                    String matchedDiscriminatorValue = ((ObjectType) t).discriminatorValue;

                    // Ensure we don't operate on nulls
                    if (discriminatorValue == null) continue;
                    if (matchedDiscriminatorValue == null) continue;

                    // Check if discriminators match
                    if (matchedDiscriminatorValue.equals(discriminatorValue)) {
                        matchedType = t;
                        break;
                    }
                }
            }

            // If we didn't match a type, return missing
            if (matchedType == null) {
                return renderValueTemplate("inst", type, scope,"union", null);
            }

            // Get the value of the object from the matching union
            String unionInstExpression = getValueExpression(value, matchedType, scope);

            // Render the union template, passing the contents of the evaluated result
            return renderValueTemplate("inst", type, scope, "union", new PrecalcValueArguments(
                value, type, scope, false, unionInstExpression
            ));

        } else if (type instanceof ScalarType) {

            // Render scalar template
            return renderValueTemplate("valueof", type, scope, ((ScalarType) type).storageType.toName(), value);

        }

        // We should never reach this point
        return "<unsupported>";
    }

//    /**
//     * Return an instance of a compiled fragment that can be used to evaluate the given
//     * fragment for the given type class.
//     *
//     * @param type     The class of type to generate
//     * @param fragment The type of the block of this type to generate
//     * @return Returns a CompiledFragment instance that can be evaluated at any time
//     * @throws Exception Throws an exception if the template could not be resolved or loaded
//     */
//    @Override
//    public CompiledFragment getTypeFragment(Class<? extends Type> type, TypeFragment fragment) throws Exception {
//        String templateName = "";
//
//        // Prefix the template name with the type kind
//        if (ArrayType.class.isAssignableFrom(type)) {
//            templateName += "array_";
//        } else if (EnumType.class.isAssignableFrom(type)) {
//            templateName += "enum_";
//        } else if (ObjectType.class.isAssignableFrom(type)) {
//            templateName += "object_";
//        } else if (ScalarType.class.isAssignableFrom(type)) {
//            templateName += "scalar_";
//        } else if (UnionType.class.isAssignableFrom(type)) {
//            templateName += "union_";
//        }
//
//        // Suffix the template name with the fragment
//        switch (fragment) {
//            case ACCESS_EXPR:
//                templateName += "access";
//                break;
//            case SIGNATURE_EXPR:
//                templateName += "signature";
//                break;
//            case CONSTRUCTOR_STMT:
//                templateName += "constructor";
//                break;
//            case DECLARATION_STMT:
//                templateName += "declaration";
//                break;
//            case INSTANTIATE_EXPR:
//                templateName += "instantiate";
//                break;
//        }
//
//        // Return new fragment instance
//        return new StringTemplateCompiledFragment(templateName);
//    }

    /**
     * Return a global fragment, that does not strictly depend on a particular type
     *
     * @param fragment The type of the compiled fragment to generate
     * @return Returns a CompiledFragment instance that can be evaluated at any time
     * @throws Exception Throws an exception if the template could not be resolved or loaded
     */
    @Override
    public CompiledFragment getCompiledFragment(CompiledFragmentType fragment) throws Exception {
        String templateName = "";

        // Resolve the enum to template name
        switch (fragment) {
            case FILE_CONTENTS:
                templateName = "contents";
                break;
            case DEBUG_ENTRYPOINT:
                templateName = "debug";
                break;
        }

        // Instantiate and return new compiled fragment
        return new StringTemplateCompiledFragment(templateName);
    }

//    /**
//     * Sanitize the given expression by passing it through the `expr` template
//     *
//     * @param expression The expression to sanitize
//     * @return
//     * @throws Exception
//     */
//    @Override
//    public String sanitizeExpression(String expression) throws Exception {
//        // If the template is missing just return the expression
//        ST tpl = group.getInstanceOf("expr");
//        if (tpl == null) {
//           return expression;
//        }
//
//        // Define the expression to sanitize
//        try {
//            tpl.add("expr", expression);
//        } catch (Exception e) {
//            return expression;
//        }
//
//        // Render the template and return the new value
//        return tpl.render();
//    }

    /**
     * The STGroupRepository is an extension to the STGroup that allows run-time file
     * resolution over the repository interface.
     */
    public static class STGroupRepository extends STGroupString {

        /**
         * A reference to the repository view
         */
        RepositoryView compilerView;

        /**
         * The name of the template
         */
        String templateName;

        /**
         * Construct a STGroup from the entry point file
         *
         * @param compilerView The compiler view from which to generate the group
         */
        private STGroupRepository(RepositoryView compilerView, String filename, char delimiterStartChar, char delimiterStopChar) throws IOException {
            super(
                    filename.substring(0, filename.length() - 4), // Strip extension
                    IOUtils.toString(compilerView.openFile(filename), "UTF-8"),
                    delimiterStartChar,
                    delimiterStopChar
            );
            this.compilerView = compilerView;
            this.templateName = filename;
        }

        /**
         * This method is called when the StringTemplate AST locates an `include` statement.
         * Such statements trigger an import
         *
         * @param fileNameToken The AST node that contains the filename to import
         */
        @Override
        public void importTemplates(Token fileNameToken) {
            String fileName = fileNameToken.getText();
            if (fileName != null && !fileName.equals("<missing STRING>")) {
                fileName = Misc.strip(fileName, 1);
                boolean isGroupFile = fileName.endsWith(GROUP_FILE_EXTENSION);
                boolean isTemplateFile = fileName.endsWith(TEMPLATE_FILE_EXTENSION);

                if (isTemplateFile) {
                    // If the file we are trying to load is a template, create an
                    // intermediate group where we are going to load the template
                    STGroup g = new STGroup(this.delimiterStartChar, this.delimiterStopChar);
                    g.setListener(this.getListener());

                    try {

                        // Parse file into a template with the same name as the file
                        g.defineTemplate(
                                fileName.substring(0, fileName.length() - 3),
                                IOUtils.toString(
                                        compilerView.openFile(fileName),
                                        "UTF-8"
                                )
                        );

                        // Satisfy import
                        this.importTemplates(g, true);

                    } catch (IOException e) {
                        System.err.println("ERROR: Unable to load StringTemplate file " + fileName + " in " + templateName + ":" + e.toString());
                        e.printStackTrace();
                    }

                } else if (isGroupFile) {
                    STGroup g = null;
                    try {

                        // Create a group
                        g = new STGroupRepository(compilerView, fileName, this.delimiterStartChar, this.delimiterStopChar);
                        g.setListener(this.getListener());

                        // Satisfy import
                        this.importTemplates(g, true);

                    } catch (IOException e) {
                        System.err.println("ERROR: Unable to load StringTemplate group " + fileName + " in " + templateName + ":" + e.toString());
                        e.printStackTrace();
                    }

                } else {
                    System.err.println("WARNING: Ignoring import directory statement in StringTemplate group " + templateName);
                }
            }
        }
    }

    /**
     * Implements formatters for the various filter engines
     */
    private static class FormattersRenderer extends StringRenderer {
        @Override
        public String toString(Object o, String formatString, Locale locale) {
            if (o == null || formatString == null) return super.toString(o, formatString, locale);
            switch (formatString) {

                // Escape java string and return
                case "_escapeFormatter":
                    return StringEscapeUtils.escapeJava((String) o);

                // Capitalize first letter, leave everything else unaffected
                case "_capitalizeFormatter":
                    String value  = o.toString();
                    return value.substring(0, 1).toUpperCase() + value.substring(1);

                default:
                    return super.toString(o, formatString, locale);
            }
        }
    }

    /**
     * A compiled statement that can be evaluated at a later time
     */
    public class StringTemplateCompiledFragment implements CompiledFragment {

        /**
         * The name of the template to render as template
         */
        private String templatenName;

        /**
         * The statement constructor
         *
         * @param templateName The name of the template to use
         */
        private StringTemplateCompiledFragment(String templateName) {
            this.templatenName = templateName;
        }

        /**
         * Compile the template and write it to the specified output stream
         *
         * @param stream The output stream where to write the block
         * @param args   The arguments to the compiler for this code block
         */
        @Override
        public void write(OutputStream stream, BlockArguments args) throws IOException {
            templateWrite(stream, templatenName, args);
        }

        @Override
        public String render(BlockArguments args) throws IOException {
            return templateRender(templatenName, args);
        }
    }

}
