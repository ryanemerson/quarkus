package io.quarkus.maven.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.shared.invoker.*;
import org.junit.jupiter.api.Test;

import io.quarkus.maven.CreateProjectMojo;
import io.quarkus.maven.utilities.MojoUtils;

class AddExtensionIT extends MojoTestBase {

    private static final String QUARKUS_VERSION = "${quarkus.version}";
    private static final String QUARKUS_GROUPID = "io.quarkus";
    private static final String VERTX_ARTIFACT_ID = "quarkus-vertx";
    private static final String COMMONS_IO = "commons-io";
    private static final String PROJECT_SOURCE_DIR = "projects/classic";
    private File testDir;
    private Invoker invoker;

    @Test
    void testAddExtensionWithASingleExtension() throws MavenInvocationException, IOException {
        testDir = initProject(PROJECT_SOURCE_DIR, "projects/testAddExtensionWithASingleExtension");
        invoker = initInvoker(testDir);
        addExtension(false, VERTX_ARTIFACT_ID);

        Model model = loadPom(testDir);
        Dependency expected = new Dependency();
        expected.setGroupId(QUARKUS_GROUPID);
        expected.setArtifactId(VERTX_ARTIFACT_ID);
        expected.setVersion(QUARKUS_VERSION);
        assertThat(contains(model.getDependencies(), expected)).isTrue();
    }

    @Test
    void testAddExtensionWithMultipleExtension() throws MavenInvocationException, IOException {
        testDir = initProject(PROJECT_SOURCE_DIR, "projects/testAddExtensionWithMultipleExtension");
        invoker = initInvoker(testDir);
        addExtension(false, "quarkus-vertx, commons-io:commons-io:2.6");

        Model model = loadPom(testDir);
        Dependency expected1 = new Dependency();
        expected1.setGroupId(QUARKUS_GROUPID);
        expected1.setArtifactId(VERTX_ARTIFACT_ID);
        expected1.setVersion(QUARKUS_VERSION);
        Dependency expected2 = new Dependency();
        expected2.setGroupId(COMMONS_IO);
        expected2.setArtifactId(COMMONS_IO);
        expected2.setVersion("2.6");
        assertThat(contains(model.getDependencies(), expected1)).isTrue();
        assertThat(contains(model.getDependencies(), expected2)).isTrue();
    }

    @Test
    void testAddExtensionWithASingleExtensionWithPluralForm() throws MavenInvocationException, IOException {
        testDir = initProject(PROJECT_SOURCE_DIR,
                "projects/testAddExtensionWithASingleExtensionWithPluralForm");
        invoker = initInvoker(testDir);
        addExtension(true, VERTX_ARTIFACT_ID);

        Model model = loadPom(testDir);
        Dependency expected = new Dependency();
        expected.setGroupId(QUARKUS_GROUPID);
        expected.setArtifactId(VERTX_ARTIFACT_ID);
        expected.setVersion(QUARKUS_VERSION);
        assertThat(contains(model.getDependencies(), expected)).isTrue();
    }

    @Test
    void testAddExtensionWithMultipleExtensionsAndPluralForm() throws MavenInvocationException, IOException {
        testDir = initProject(PROJECT_SOURCE_DIR,
                "projects/testAddExtensionWithMultipleExtensionAndPluralForm");
        invoker = initInvoker(testDir);
        addExtension(true, "quarkus-vertx, commons-io:commons-io:2.6");

        Model model = loadPom(testDir);
        Dependency expected1 = new Dependency();
        expected1.setGroupId(QUARKUS_GROUPID);
        expected1.setArtifactId(VERTX_ARTIFACT_ID);
        expected1.setVersion(QUARKUS_VERSION);
        Dependency expected2 = new Dependency();
        expected2.setGroupId(COMMONS_IO);
        expected2.setArtifactId(COMMONS_IO);
        expected2.setVersion("2.6");
        assertThat(contains(model.getDependencies(), expected1)).isTrue();
        assertThat(contains(model.getDependencies(), expected2)).isTrue();
    }

    private boolean contains(List<Dependency> dependencies, Dependency expected) {
        return dependencies.stream().anyMatch(dep -> dep.getGroupId().equalsIgnoreCase(expected.getGroupId())
                && dep.getArtifactId().equalsIgnoreCase(expected.getArtifactId())
                && dep.getVersion().equalsIgnoreCase(expected.getVersion())
                && (dep.getScope() == null || dep.getScope().equalsIgnoreCase(expected.getScope()))
                && dep.isOptional() == expected.isOptional()
                && dep.getType().equalsIgnoreCase(expected.getType()));
    }

    private void addExtension(boolean plural, String ext)
            throws MavenInvocationException, FileNotFoundException, UnsupportedEncodingException {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setBatchMode(true);
        request.setGoals(Collections.singletonList(
                CreateProjectMojo.getPluginKey() + ":" + MojoUtils.getPluginVersion() + ":add-extension"));
        Properties properties = new Properties();
        if (plural) {
            properties.setProperty("extensions", ext);
        } else {
            properties.setProperty("extension", ext);
        }
        request.setProperties(properties);
        getEnv().forEach(request::addShellEnvironment);
        File log = new File(testDir, "build-add-extension-" + testDir.getName() + ".log");
        PrintStreamLogger logger = new PrintStreamLogger(new PrintStream(new FileOutputStream(log), false, "UTF-8"),
                InvokerLogger.DEBUG);
        invoker.setLogger(logger);
        invoker.execute(request);
    }
}
