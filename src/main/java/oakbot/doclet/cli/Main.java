package oakbot.doclet.cli;

import static oakbot.util.JunkDrawer.WINDOWS_OS;
import static oakbot.util.JunkDrawer.rmdirRecursive;
import static oakbot.util.JunkDrawer.unzip;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import oakbot.doclet.ConfigProperties;
import oakbot.doclet.OakbotDoclet;
import oakbot.util.Downloader;

/**
 * A command-line interface for generating a Javadoc ZIP file for OakBot.
 * @author Michael Angstadt
 */
public class Main {
	private static final Console console = new Console();
	private static Path tempDir;

	public static void main(String args[]) throws Exception {
		Arguments arguments = new Arguments(args);
		if (arguments.help()) {
			arguments.printHelp();
			return;
		}

		String javadocExe = getJavadocExe();

		Input input;
		if (arguments.interactive()) {
			input = new InteractiveInput(console);
		} else {
			input = new ArgumentInput(arguments);
		}

		InputParameters inputParameters;
		try {
			inputParameters = input.read();
		} catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			System.exit(1);
			return;
		}

		tempDir = Files.createTempDirectory("oakbot.doclet");

		try {
			Path source = inputParameters.getSource();
			List<Path> dependencyJars;
			if (source == null) {
				MavenLibrary maven = inputParameters.getMavenLibrary();
				source = downloadSourceJar(maven);
				Path pom = downloadPom(maven);
				dependencyJars = downloadDependencies(pom);
			} else {
				dependencyJars = Collections.emptyList();
			}

			Path sourceDir = Files.isDirectory(source) ? source : unzipSource(source);

			ConfigProperties systemProperties = new ConfigProperties();
			systemProperties.setOutputPath(inputParameters.getOutput());
			systemProperties.setPrettyPrint(inputParameters.isPrettyPrint());
			systemProperties.setLibraryName(inputParameters.getName());
			systemProperties.setLibraryVersion(inputParameters.getVersion());
			if (!inputParameters.getJavadocUrl().isEmpty()) {
				systemProperties.setLibraryBaseUrl(inputParameters.getJavadocUrl());
			}
			if (!inputParameters.getJavadocUrlPattern().isEmpty()) {
				systemProperties.setLibraryJavadocUrlPattern(inputParameters.getJavadocUrlPattern());
			}
			if (!inputParameters.getWebsite().isEmpty()) {
				systemProperties.setProjectUrl(inputParameters.getWebsite());
			}

			List<String> command = new JavadocCommandBuilder(javadocExe) //@formatter:off
				.doclet(OakbotDoclet.class.getName())
				.docletClasspath(getClasspath())
				.source(sourceDir.toString())
				.sourceEncoding("UTF-8")
				.sourceDependenciesClasspath(buildClasspath(dependencyJars))
				.includePackages(getSubpackages(sourceDir))
				.excludePackages(inputParameters.getExcludePackages())
				.systemProperties(systemProperties)
				.maxHeapSize(1024)
			.build(); //@formatter:on

			runJavadoc(command, arguments.verbose());
		} finally {
			console.printf("Cleaning up...");
			rmdirRecursive(tempDir);
			console.printf("done.%n");
		}
	}

	private static class JavadocCommandBuilder {
		private final List<String> command = new ArrayList<>();

		public JavadocCommandBuilder(String javadocExe) {
			command.add(javadocExe);
		}

		public JavadocCommandBuilder doclet(String className) {
			return param("doclet", className);
		}

		public JavadocCommandBuilder docletClasspath(String classpath) {
			return param("docletpath", classpath);
		}

		public JavadocCommandBuilder source(String sourcepath) {
			return param("sourcepath", sourcepath);
		}

		public JavadocCommandBuilder sourceEncoding(String charset) {
			return param("encoding", charset);
		}

		public JavadocCommandBuilder sourceDependenciesClasspath(String classpath) {
			return param("classpath", classpath);
		}

		public JavadocCommandBuilder includePackages(List<String> subpackages) {
			for (String subpackage : subpackages) {
				param("subpackages", subpackage);
			}
			return this;
		}

		public JavadocCommandBuilder excludePackages(List<String> subpackages) {
			for (String subpackage : subpackages) {
				param("exclude", subpackage);
			}
			return this;
		}

		public JavadocCommandBuilder systemProperties(Iterable<Map.Entry<String, String>> it) {
			for (Map.Entry<String, String> entry : it) {
				command.add("-J-D" + entry.getKey() + "=" + entry.getValue());
			}
			return this;
		}

		public JavadocCommandBuilder maxHeapSize(int mb) {
			command.add("-J-Xmx" + mb + "m");
			return this;
		}

		public List<String> build() {
			return command;
		}

		private JavadocCommandBuilder param(String name, String value) {
			if (!value.isEmpty()) {
				command.add("-" + name);
				command.add(value);
			}
			return this;
		}
	}

	private static void runJavadoc(List<String> command, boolean verbose) throws IOException, InterruptedException {
		if (verbose) {
			console.printf("Starting doclet: " + command + "%n");
		} else {
			console.printf("Starting doclet...%n");
		}

		ProcessBuilder builder = new ProcessBuilder(command);
		Process process = builder.start();
		pipeOutput(process);
		process.waitFor();
	}

	/**
	 * Downloads the dependencies defined in the given POM file.
	 * @param pom the POM file
	 * @return the path to the downloaded JARs
	 * @throws IOException if there's a problem downloading the dependencies
	 * @throws InterruptedException if the Maven command was interrupted
	 */
	private static List<Path> downloadDependencies(Path pom) throws IOException, InterruptedException {
		//use Maven to perform the dependency resolution
		String executable = WINDOWS_OS ? "mvn.cmd" : "mvn";
		ProcessBuilder builder = new ProcessBuilder(executable, "dependency:copy-dependencies");
		builder.directory(pom.getParent().toFile());

		Process process = builder.start();
		pipeOutput(process);
		int exitValue = process.waitFor();
		if (exitValue != 0) {
			throw new RuntimeException("Maven processed failed.");
		}

		Path dependencyDir = tempDir.resolve(Paths.get("target", "dependency"));
		if (!Files.exists(dependencyDir)) {
			return Collections.emptyList();
		}

		//build a list of all the JARs
		List<Path> jars = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dependencyDir, entry -> {
			String filename = entry.getFileName().toString();
			return filename.endsWith(".jar");
		})) {
			stream.forEach(jars::add);
		}
		return jars;
	}

	/**
	 * Download the library's POM file from Maven Central.
	 * @param library the library
	 * @return the path to the downloaded file
	 * @throws IOException if there's a problem downloading the file
	 */
	private static Path downloadPom(MavenLibrary library) throws IOException {
		console.printf("Downloading project POM...");

		Path dest = tempDir.resolve("pom.xml");
		String url = library.getPomUrl();
		new Downloader(url, dest).start();

		console.printf("done.%n");
		return dest;
	}

	/**
	 * Downloads the source code JAR of the given library from Maven Central.
	 * @param library the library
	 * @return the location of the downloaded file
	 * @throws IOException if there's a problem downloading the file
	 */
	private static Path downloadSourceJar(MavenLibrary library) throws IOException {
		String url = library.getSourcesUrl();
		int pos = url.lastIndexOf('/');
		String filename = url.substring(pos + 1);
		Path dest = tempDir.resolve(filename);

		new Downloader(url, dest).onProgress((downloaded, size) -> {
			long downloadedKb = downloaded / 1024;
			if (size > 0) {
				int percent = (int) ((double) downloaded / size * 100);
				long sizeKb = size / 1024;
				console.printf("\rDownloading " + filename + " (" + downloadedKb + "KB / " + sizeKb + "KB, " + percent + "%%)");
			} else {
				console.printf("\rDownloading " + filename + " (" + downloadedKb + "KB)");
			}
		}).start();

		console.printf("%n");

		return dest;
	}

	/**
	 * Gets the path to the javadoc executable.
	 * @return the path
	 */
	private static String getJavadocExe() {
		String javaHomeEnv = System.getenv("JAVA_HOME");
		if (javaHomeEnv == null) {
			die("Please set your JAVA_HOME environment variable.");
		}

		String executable = WINDOWS_OS ? "javadoc.exe" : "javadoc";
		Path javadoc = Paths.get(javaHomeEnv, "bin", executable);
		if (!Files.exists(javadoc)) {
			die("The JAVA_HOME environment variable does not point to a valid JDK (no \"javadoc\" executable found): " + javaHomeEnv);
		}

		return javadoc.toString();
	}

	/**
	 * Extracts a source code JAR file.
	 * @param sourceJar the JAR file
	 * @return the path to the extracted files
	 * @throws IOException if there's a problem extracting the files
	 */
	private static Path unzipSource(Path sourceJar) throws IOException {
		console.printf("Extracting files from source archive...");

		Path dir = tempDir.resolve("src");
		Files.createDirectory(dir);
		unzip(dir, sourceJar);

		console.printf("done.%n");
		return dir;
	}

	/**
	 * Gets the classpath of the currently running program.
	 * @return the classpath string
	 */
	private static String getClasspath() {
		URL urls[] = ((URLClassLoader) (Thread.currentThread().getContextClassLoader())).getURLs();
		List<Path> paths = new ArrayList<>(urls.length);
		for (URL url : urls) {
			String urlPath = url.getPath();
			if (WINDOWS_OS) {
				//example URL: /C:/Users/mikea/OakBot/workspace/oakbot-doclet/target/oakbot-doclet-0.0.4-jar-with-dependencies.jar
				//@formatter:off
				urlPath = urlPath
					.substring(1) //remove the first slash
					.replace('/', '\\'); //use backslashes
				//@formatter:on
			}

			Path path = Paths.get(urlPath);
			paths.add(path);
		}

		return buildClasspath(paths);
	}

	/**
	 * Gets the names of all the top-level packages in a given folder.
	 * @param sourceDir the directory containing the source code
	 * @return the top-level sub packages
	 * @throws IOException if there's a problem reading the directory
	 */
	private static List<String> getSubpackages(Path sourceDir) throws IOException {
		List<String> subpackages = new ArrayList<>();

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceDir, entry -> {
			if (!Files.isDirectory(entry)) {
				return false;
			}

			String filename = entry.getFileName().toString();
			return !filename.equals("META-INF");
		})) {
			stream.forEach(path -> subpackages.add(path.getFileName().toString()));
		}

		return subpackages;
	}

	/**
	 * Builds a classpath string.
	 * @param paths the files and directories to include in the classpath
	 * @return the classpath string
	 */
	private static String buildClasspath(Collection<Path> paths) {
		List<String> pathStrings = paths.stream().map(Path::toString).collect(Collectors.toList());

		String separator = System.getProperty("path.separator");
		return String.join(separator, pathStrings);
	}

	/**
	 * Prints a message to stderr and exits the program with an error status
	 * code.
	 * @param message the message
	 */
	private static void die(String message) {
		System.err.println(message);
		System.exit(1);
	}

	/**
	 * Pipes the output of a process to this program's stdout and stderr
	 * streams.
	 * @param process the process
	 */
	private static void pipeOutput(Process process) {
		//TODO not thread safe, messages get jumbled
		PipeThread stdout = new PipeThread(process.getInputStream(), System.out);
		PipeThread stderr = new PipeThread(process.getErrorStream(), System.err);
		stdout.start();
		stderr.start();
	}

	private static class PipeThread extends Thread {
		private final InputStream in;
		private final PrintStream out;

		public PipeThread(InputStream in, PrintStream out) {
			this.in = in;
			this.out = out;
			setDaemon(true);
		}

		@Override
		public void run() {
			try (InputStream in = new BufferedInputStream(this.in)) {
				int read;
				while ((read = in.read()) != -1) {
					out.print((char) read);
				}
			} catch (IOException ignore) {
				//shouldn't be thrown
			}
		}
	}
}
