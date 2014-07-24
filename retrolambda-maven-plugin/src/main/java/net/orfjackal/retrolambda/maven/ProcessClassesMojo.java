// Copyright © 2013-2014 Esko Luontola <www.orfjackal.net>
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

package net.orfjackal.retrolambda.maven;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.*;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.*;

import java.io.*;
import java.util.*;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

abstract class ProcessClassesMojo extends AbstractMojo {

    private static final Map<String, Integer> targetBytecodeVersions = ImmutableMap.of(
            "1.5", 49,
            "1.6", 50,
            "1.7", 51,
            "1.8", 52
    );

    @Component
    ToolchainManager toolchainManager;

    @Component
    private MavenSession session;

    @Component
    private BuildPluginManager pluginManager;

    @Component
    private MavenProject project;

    /**
     * Directory of the Java 8 installation for running Retrolambda.
     * The JRE to be used will be determined in priority order:
     * <ol>
     * <li>This parameter</li>
     * <li><a href="http://maven.apache.org/plugins/maven-toolchains-plugin/toolchains/jdk.html">JDK toolchain</a></li>
     * <li>Same as Maven</li>
     * </ol>
     *
     * @since 1.2.0
     */
    @Parameter(property = "java8home", required = false)
    public File java8home;

    /**
     * The Java version targeted by the bytecode processing. Possible values are
     * 1.5, 1.6, 1.7 and 1.8. After processing the classes will be compatible
     * with the target JVM provided the known limitations are considered. See
     * <a href="https://github.com/orfjackal/retrolambda">project documentation</a>
     * for more details.
     *
     * @since 1.2.0
     */
    @Parameter(defaultValue = "1.7", property = "retrolambdaTarget", required = true)
    public String target;

    protected abstract File getInputDir();

    protected abstract File getOutputDir();

    protected abstract String getClasspathId();

    @Override
    public void execute() throws MojoExecutionException {
        validateTarget();

        String version = getRetrolambdaVersion();
        getLog().info("Retrieving Retrolambda " + version);
        retrieveRetrolambdaJar(version);

        getLog().info("Processing classes with Retrolambda");
        processClasses();
    }

    String getJavaCommand() {
        String javaCommand = getJavaCommand(new File(System.getProperty("java.home")));

        Toolchain tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
        if (tc != null) {
            getLog().info("Toolchain in retrolambda-maven-plugin: " + tc);
            javaCommand = tc.findTool("java");
        }

        if (java8home != null) {
            if (tc != null) {
                getLog().warn("Toolchains are ignored, 'java8home' parameter is set to " + java8home);
            }
            javaCommand = getJavaCommand(java8home);
        }
        return javaCommand;
    }

    private static String getJavaCommand(File javaHome) {
        return new File(javaHome, "bin/java").getPath();
    }

    private void validateTarget() throws MojoExecutionException {
        if (!targetBytecodeVersions.containsKey(target)) {
            String possibleValues = Joiner.on(", ").join(new TreeSet<String>(targetBytecodeVersions.keySet()));
            throw new MojoExecutionException(
                    "Unrecognized target '" + target + "'. Possible values are " + possibleValues);
        }
    }

    private void retrieveRetrolambdaJar(String version) throws MojoExecutionException {
        // TODO: use Maven's built-in artifact resolving, so that we can refer to retrolambda.jar in the local repository without copying it
        executeMojo(
                plugin(groupId("org.apache.maven.plugins"),
                        artifactId("maven-dependency-plugin"),
                        version("2.8")),
                goal("copy"),
                configuration(element("artifactItems",
                        element("artifactItem",
                                element(name("groupId"), "net.orfjackal.retrolambda"),
                                element(name("artifactId"), "retrolambda"),
                                element(name("version"), version),
                                element(name("overWrite"), "true"),
                                element(name("outputDirectory"), getRetrolambdaJarDir()),
                                element(name("destFileName"), getRetrolambdaJarName())))),
                executionEnvironment(project, session, pluginManager));
    }

    private void processClasses() throws MojoExecutionException {
        String retrolambdaJar = getRetrolambdaJarPath();
        executeMojo(
                plugin(groupId("org.apache.maven.plugins"),
                        artifactId("maven-antrun-plugin"),
                        version("1.7")),
                goal("run"),
                configuration(element(
                        "target",
                        element("property",
                                attributes(attribute("name", "the_classpath"),
                                        attribute("refid", getClasspathId()))),
                        element("exec",
                                attributes(
                                        attribute("executable", getJavaCommand()),
                                        attribute("failonerror", "true")),
                                element("arg", attribute("value", "-Dretrolambda.bytecodeVersion=" + targetBytecodeVersions.get(target))),
                                element("arg", attribute("value", "-Dretrolambda.inputDir=" + getInputDir().getAbsolutePath())),
                                element("arg", attribute("value", "-Dretrolambda.outputDir=" + getOutputDir().getAbsolutePath())),
                                element("arg", attribute("value", "-Dretrolambda.classpath=${the_classpath}")),
                                element("arg", attribute("value", "-javaagent:" + retrolambdaJar)),
                                element("arg", attribute("value", "-jar")),
                                element("arg", attribute("value", retrolambdaJar))))),
                executionEnvironment(project, session, pluginManager));
    }

    private String getRetrolambdaJarPath() {
        return getRetrolambdaJarDir() + "/" + getRetrolambdaJarName();
    }

    private String getRetrolambdaJarDir() {
        return project.getBuild().getDirectory() + "/retrolambda";
    }

    private String getRetrolambdaJarName() {
        return "retrolambda.jar";
    }

    private static String getRetrolambdaVersion() throws MojoExecutionException {
        try {
            InputStream is = ProcessClassesMojo.class.getResourceAsStream(
                    "/META-INF/maven/net.orfjackal.retrolambda/retrolambda-maven-plugin/pom.properties");
            try {
                Properties p = new Properties();
                p.load(is);
                return p.getProperty("version");
            } finally {
                is.close();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to detect the Retrolambda version", e);
        }
    }
}