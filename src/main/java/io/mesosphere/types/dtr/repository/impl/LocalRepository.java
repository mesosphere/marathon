package io.mesosphere.types.dtr.repository.impl;

import io.mesosphere.types.dtr.repository.internal.Repository;
import io.mesosphere.types.dtr.repository.internal.RepositoryFile;
import io.mesosphere.types.dtr.repository.internal.RepositoryView;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An implementation of the Repository class that uses a local directory as
 * the persistence layer. (For instance the `example` folder).
 */
public class LocalRepository extends Repository {

    /**
     * Base directory where to search for projects
     */
    private Path baseDir;

    /**
     * Construct a local repository on the specified base directory
     *
     * @param baseDir The base directory where the repository is located
     */
    public LocalRepository(String baseDir) throws IllegalArgumentException {
        this.baseDir = Paths.get(baseDir).toAbsolutePath();
        if (!Files.isDirectory(this.baseDir)) {
            throw new IllegalArgumentException("The specified repository directory (" + this.baseDir.toString() + ") does not exist");
        }
    }

    /**
     * Open a local repository view for the given project name and type version
     *
     * @param project The name of the project to open
     * @param version The version of the types to open
     * @return Return a repository view within the `projects/{project}/types/{version}/types` folder
     */
    @Override
    public RepositoryView openProjectTypes(String project, String version) {
        Path projectPath = Paths.get(this.baseDir.toString(), "projects",  project);
        if (!Files.isDirectory(projectPath)) {
            throw new IllegalArgumentException("The specified project (" + project + ") was not found in the repository");
        }

        Path versionPath = Paths.get(projectPath.toString(), version);
        if (!Files.isDirectory(projectPath)) {
            throw new IllegalArgumentException("The specified version (" + version + ") was not found in the repository for project " + project);
        }

        Path typePath = Paths.get(versionPath.toString(), "types");
        if (!Files.isDirectory(projectPath)) {
            throw new IllegalArgumentException("The specified version (" + version + ") does not contain a types folder for project " + project);
        }

        return new LocalRepositoryView(typePath);
    }

    /**
     * Open a local repository view for the given project name and compiler version
     *
     * @param project The name of the project to open
     * @param version The version of the compiler to open
     * @return Return a repository view within the `projects/{project}/compiler/{version}` folder
     */
    @Override
    public RepositoryView openProjectCompiler(String project, String version) {
        Path projectPath = Paths.get(this.baseDir.toString(), "projects",  project);
        if (!Files.isDirectory(projectPath)) {
            throw new IllegalArgumentException("The specified project (" + project + ") was not found in the repository");
        }

        Path versionPath = Paths.get(projectPath.toString(), version);
        if (!Files.isDirectory(projectPath)) {
            throw new IllegalArgumentException("The specified version (" + version + ") was not found in the repository for project " + project);
        }

        Path compilerPath = Paths.get(versionPath.toString(), "compiler");
        if (!Files.isDirectory(projectPath)) {
            throw new IllegalArgumentException("The specified version (" + version + ") does not contain a compiler folder for project " + project);
        }

        return new LocalRepositoryView(compilerPath);
    }

    /**
     * An implementation of RepositoryFile for local files
     */
    public static class LocalRepositoryFile implements RepositoryFile {

        /**
         * The filename
         */
        private String filename;

        /**
         * Simple constructor that uses a path to a local file as argument
         * @param filename
         */
        public LocalRepositoryFile(String filename) {
            this.filename = filename;
        }

        /**
         * Return the filename
         * @return
         */
        @Override
        public String getName() {
            return filename;
        }

        /**
         * Try to resulve the Mime-Type of the given filename
         * @return
         */
        @Override
        public String getMimeType() {
            if (filename.endsWith(".raml")) {
                return "application/raml+yaml";
            } else if (filename.endsWith("json")) {
                return "text/json";
            } else {
                return "text/plain";
            }
        }

    }

    /**
     * A view to a sub-directory of the repository
     */
    public static class LocalRepositoryView implements RepositoryView {
        /**
         * Base directory where to search for project files
         */
        private Path baseDir;

        /**
         * Constructor
         * @param baseDir Base directory where this view starts
         */
        public LocalRepositoryView(Path baseDir) {
            this.baseDir = baseDir;
        }

        /**
         * List the files in the directory
         * @return A list of files in this directory
         */
        @Override
        public List<RepositoryFile> listFiles() {
            String baseDirStr = baseDir.toString();
            File rootDir = new File(baseDirStr);
            Collection<File> files = FileUtils.listFiles(rootDir, null, true);

            return files
                    .stream()
                    .map(file -> new LocalRepositoryFile(file.toString().substring(baseDirStr.length() + 1)))
                    .collect(Collectors.toList());
        }

        /**
         * Open an input stream to the contents of the type file specified
         * @param filename The filename of the type to load
         *
         * @return An input stream from which to read the contents of the file
         * @throws FileNotFoundException When the file you are trying to access is missing
         */
        @Override
        public InputStream openFile(String filename) throws FileNotFoundException {
            File inputFile = new File(Paths.get(this.baseDir.toString(), filename).toString());
            return new FileInputStream(inputFile);
        }

        /**
         * Check if the specified file exists
         * @param filename The filename to check
         * @return Returns True if the file exists, or false otherwise
         */
        @Override
        public Boolean exists(String filename) {
            return new File(Paths.get(baseDir.toString(), filename).toString()).exists();
        }
    }
}
