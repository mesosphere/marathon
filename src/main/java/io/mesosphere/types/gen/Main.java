package io.mesosphere.types.gen;

import io.mesosphere.types.gen.compiler.impl.StringTemplateEngine;
import io.mesosphere.types.gen.compiler.internal.CompiledFragment;
import io.mesosphere.types.gen.compiler.internal.CompilerEngine;
import io.mesosphere.types.gen.compiler.internal.CompiledFragmentType;
import io.mesosphere.types.gen.compiler.internal.arguments.FileObjectTypeFragmentArguments;
import io.mesosphere.types.gen.models.internal.Type;
import io.mesosphere.types.gen.models.internal.TypeScope;
import io.mesosphere.types.gen.models.internal.scopes.FileScope;
import io.mesosphere.types.gen.models.internal.scopes.GlobalScope;
import io.mesosphere.types.gen.models.internal.types.*;
import io.mesosphere.types.gen.models.parser.RAMLParser;
import io.mesosphere.types.gen.repository.internal.Repository;
import io.mesosphere.types.gen.repository.internal.RepositoryView;
import org.apache.commons.cli.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;

public class Main {

    /**
     * Entry point
     * @param args
     */
    public static void main(String[] args) throws ParseException {
        Options options = new Options();
        CommandLineParser parser = new DefaultParser();

        // Prepare options
        Option o_help = Option.builder("h")
                .longOpt("help")
                .desc("Print this help message")
                .argName("help")
                .build();

        Option o_project = Option.builder("p")
                .longOpt("project")
                .required()
                .desc("The project name to target")
                .hasArg()
                .argName("project")
                .build();

        Option o_version = Option.builder("v")
                .longOpt("version")
                .required()
                .desc("The project version to use")
                .hasArg()
                .argName("version")
                .build();

        Option o_repo = Option.builder("r")
                .longOpt("repo")
                .required()
                .desc("The repository URI to use")
                .hasArg()
                .argName("repo")
                .build();

        Option o_target = Option.builder("t")
                .longOpt("target")
                .required()
                .desc("The target directory where to write down the types")
                .hasArg()
                .argName("target")
                .build();

        // Compose options
        options.addOption(o_help);
        options.addOption(o_project);
        options.addOption(o_version);
        options.addOption(o_repo);
        options.addOption(o_target);

        // Parse options
        try {
            CommandLine cmd = parser.parse(options, args);

            // Handle and bail early on hep argument
            if (cmd.hasOption("help")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp( "dtr", options );
                return;
            }

            // Create a repository that is going to be our access
            // channel to the type registry.
            Repository repo = Repository.fromURI(cmd.getOptionValue("repo"));

            // Access the specified project & version
            RepositoryView project = repo.openProjectTypes(
                cmd.getOptionValue("project"),
                cmd.getOptionValue("version")
            );

            // Load project
            RAMLParser raml = new RAMLParser();
            GlobalScope globalScope = new GlobalScope();
            raml.loadProjectTypes(project, globalScope);

            // Initialize template
            RepositoryView compilerView = repo.openProjectCompiler(
                cmd.getOptionValue("project"),
                cmd.getOptionValue("version")
            );
            CompilerEngine engine = new StringTemplateEngine(compilerView);
            CompiledFragment frag = engine.getCompiledFragment(CompiledFragmentType.FILE_CONTENTS);

            // Get the target folder
            String baseDir = cmd.getOptionValue("target");

            // Display type registry
            for (TypeScope scope: globalScope.childScopesOfType(FileScope.class)) {
                for (Map.Entry<String, Type> t: scope.flatTypeMap().entrySet()) {
                    Type type = t.getValue();
                    StructuralType sType = type.getStructural();

                    if (!(sType instanceof ScalarType) && !(sType instanceof ArrayType)) {
                        FileOutputStream fos = new FileOutputStream(baseDir + type.getId() + ".scala");
                        FileObjectTypeFragmentArguments t_args = new FileObjectTypeFragmentArguments(type, globalScope, engine);
                        frag.write(fos, t_args);
                        fos.close();
                    }
                }
            }

        } catch (UnrecognizedOptionException e) {
            System.err.println("ERROR: Unrecognized option " + e.getOption());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("dtr", options);

        } catch (MissingArgumentException e) {
            System.err.println("ERROR: Missing value for option " + e.getOption().getArgName());

        } catch (MissingOptionException e) {
            String missingStr = "-" + String.join(", -", e.getMissingOptions());
            System.err.println("ERROR: Please provide at least the following options: " + missingStr);
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp( "dtr", options );
        } catch (IOException e) {
            System.err.println("ERROR: An I/O Error occurred " + e.toString());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("ERROR: An Unexpected error occurred " + e.toString());
            e.printStackTrace();
        }

    }

}
