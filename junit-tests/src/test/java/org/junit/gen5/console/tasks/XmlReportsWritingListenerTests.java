/*
 * Copyright 2015-2016 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.junit.gen5.console.tasks;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.gen5.api.Assertions.assertTrue;
import static org.junit.gen5.api.Assertions.fail;
import static org.junit.gen5.api.Assumptions.assumeFalse;
import static org.junit.gen5.console.tasks.XmlReportAssertions.ensureValidAccordingToJenkinsSchema;
import static org.junit.gen5.engine.TestExecutionResult.successful;
import static org.junit.gen5.engine.discovery.UniqueIdSelector.forUniqueId;
import static org.junit.gen5.launcher.main.LauncherFactoryForTestingPurposesOnly.createLauncher;
import static org.junit.gen5.launcher.main.TestDiscoveryRequestBuilder.request;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.gen5.api.AfterEach;
import org.junit.gen5.api.BeforeEach;
import org.junit.gen5.api.Test;
import org.junit.gen5.api.TestInfo;
import org.junit.gen5.engine.TestEngine;
import org.junit.gen5.engine.support.descriptor.EngineDescriptor;
import org.junit.gen5.engine.support.hierarchical.DummyTestDescriptor;
import org.junit.gen5.engine.support.hierarchical.DummyTestEngine;
import org.junit.gen5.launcher.Launcher;
import org.junit.gen5.launcher.TestIdentifier;
import org.junit.gen5.launcher.TestPlan;
import org.opentest4j.AssertionFailedError;

class XmlReportsWritingListenerTests {

	private Path tempDirectory;

	@BeforeEach
	void createTempDirectory(TestInfo testInfo) throws Exception {
		tempDirectory = Files.createTempDirectory(testInfo.getName());
	}

	@AfterEach
	void deleteTempDirectory() throws Exception {
		Files.walkFileTree(tempDirectory, new SimpleFileVisitor<Path>() {

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				return deleteAndContinue(file);
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				return deleteAndContinue(dir);
			}

			private FileVisitResult deleteAndContinue(Path path) throws IOException {
				Files.delete(path);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	@Test
	void writesFileForSingleSucceedingTest() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		engine.addTest("succeedingTest", () -> {
		}).setDisplayName("display<>Name");

		executeTests(engine);

		String content = readValidXmlFile("TEST-dummy.xml");
		//@formatter:off
		assertThat(content)
			.containsSequence(
				"<testsuite name=\"dummy\" tests=\"1\" skipped=\"0\" failures=\"0\" errors=\"0\"",
				"<!--Unique ID: dummy-->",
				"<testcase name=\"display&lt;&gt;Name\" classname=\"dummy\"",
				"<!--Unique ID: dummy:succeedingTest-->",
				"</testcase>",
				"</testsuite>")
			.doesNotContain("<skipped")
			.doesNotContain("<failure")
			.doesNotContain("<error");
		//@formatter:on
	}

	@Test
	void writesFileForSingleFailingTest() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		engine.addTest("failingTest", () -> fail("expected to <b>fail</b>"));

		executeTests(engine);

		String content = readValidXmlFile("TEST-dummy.xml");
		//@formatter:off
		assertThat(content)
			.containsSequence(
				"<testsuite name=\"dummy\" tests=\"1\" skipped=\"0\" failures=\"1\" errors=\"0\"",
				"<testcase name=\"failingTest\"",
				"<failure message=\"expected to &lt;b&gt;fail&lt;/b&gt;\" type=\"" + AssertionFailedError.class.getName() + "\">",
				"AssertionFailedError: expected to &lt;b&gt;fail&lt;/b&gt;",
				"\tat",
				"</failure>",
				"</testcase>",
				"</testsuite>")
			.doesNotContain("<skipped")
			.doesNotContain("<error");
		//@formatter:on
	}

	@Test
	void writesFileForSingleErroneousTest() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		engine.addTest("failingTest", () -> {
			throw new RuntimeException("error occurred");
		});

		executeTests(engine);

		String content = readValidXmlFile("TEST-dummy.xml");
		//@formatter:off
		assertThat(content)
			.containsSequence(
				"<testsuite name=\"dummy\" tests=\"1\" skipped=\"0\" failures=\"0\" errors=\"1\"",
				"<testcase name=\"failingTest\"",
				"<error message=\"error occurred\" type=\"java.lang.RuntimeException\">",
				"RuntimeException: error occurred",
				"\tat ",
				"</error>",
				"</testcase>",
				"</testsuite>")
			.doesNotContain("<skipped")
			.doesNotContain("<failure");
		//@formatter:on
	}

	@Test
	void writesFileForSingleSkippedTest() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		DummyTestDescriptor testDescriptor = engine.addTest("skippedTest", () -> fail("never called"));
		testDescriptor.markSkipped("should be skipped");

		executeTests(engine);

		String content = readValidXmlFile("TEST-dummy.xml");
		//@formatter:off
		assertThat(content)
			.containsSequence(
				"<testsuite name=\"dummy\" tests=\"1\" skipped=\"1\" failures=\"0\" errors=\"0\"",
				"<testcase name=\"skippedTest\"",
				"<skipped>should be skipped</skipped>",
				"</testcase>",
				"</testsuite>")
			.doesNotContain("<failure")
			.doesNotContain("<error");
		//@formatter:on
	}

	@Test
	void writesFileForSingleAbortedTest() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		engine.addTest("abortedTest", () -> assumeFalse(true, "deliberately aborted"));

		executeTests(engine);

		String content = readValidXmlFile("TEST-dummy.xml");
		//@formatter:off
		assertThat(content)
			.containsSequence(
				"<testsuite name=\"dummy\" tests=\"1\" skipped=\"1\" failures=\"0\" errors=\"0\"",
				"<testcase name=\"abortedTest\"",
				"<skipped>",
				"TestAbortedException: ",
				"deliberately aborted",
				"at ",
				"</skipped>",
				"</testcase>",
				"</testsuite>")
			.doesNotContain("<failure")
			.doesNotContain("<error");
		//@formatter:on
	}

	@Test
	void measuresTimesInSeconds() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		engine.addTest("firstTest", () -> {
		});
		engine.addTest("secondTest", () -> {
		});

		executeTests(engine, new IncrementingClock(0, Duration.ofMillis(333)));

		String content = readValidXmlFile("TEST-dummy.xml");

		//@formatter:off
		//               start        end
		// ----------- ---------- -----------
		// engine          0 (1)    1,665 (6)
		// firstTest     333 (2)      666 (3)
		// secondTest    999 (4)    1,332 (5)
		assertThat(content)
			.containsSequence(
				"<testsuite", "time=\"1.665\"",
				"<testcase name=\"firstTest\" classname=\"dummy\" time=\"0.333\"",
				"<testcase name=\"secondTest\" classname=\"dummy\" time=\"0.333\"");
		//@formatter:on
	}

	@Test
	void testWithImmeasurableTimeIsOutputCorrectly() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		engine.addTest("test", () -> {
		});

		executeTests(engine, Clock.fixed(Instant.EPOCH, ZoneId.systemDefault()));

		String content = readValidXmlFile("TEST-dummy.xml");

		//@formatter:off
		assertThat(content)
			.containsSequence(
				"<testsuite",
				"<testcase name=\"test\" classname=\"dummy\" time=\"0\"");
		//@formatter:on
	}

	@Test
	void writesFileForSkippedContainer() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		engine.addTest("test", () -> fail("never called"));
		engine.getEngineDescriptor().markSkipped("should be skipped");

		executeTests(engine);

		String content = readValidXmlFile("TEST-dummy.xml");
		//@formatter:off
		assertThat(content)
			.containsSequence(
				"<testsuite name=\"dummy\" tests=\"1\" skipped=\"1\" failures=\"0\" errors=\"0\"",
				"<testcase name=\"test\"",
				"<skipped>parent was skipped: should be skipped</skipped>",
				"</testcase>",
				"</testsuite>");
		//@formatter:on
	}

	@Test
	void writesFileForFailingContainer() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		engine.addTest("test", () -> fail("never called"));
		engine.getEngineDescriptor().setBeforeAllBehavior(() -> fail("failure before all tests"));

		executeTests(engine);

		String content = readValidXmlFile("TEST-dummy.xml");
		//@formatter:off
		assertThat(content)
			.containsSequence(
				"<testsuite name=\"dummy\" tests=\"1\" skipped=\"0\" failures=\"1\" errors=\"0\"",
				"<testcase name=\"test\"",
				"<failure message=\"failure before all tests\" type=\"" + AssertionFailedError.class.getName() + "\">",
				"AssertionFailedError: failure before all tests",
				"\tat",
				"</failure>",
				"</testcase>",
				"</testsuite>");
		//@formatter:on
	}

	@Test
	void writesSystemProperties() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		engine.addTest("test", () -> {
		});

		executeTests(engine);

		String content = readValidXmlFile("TEST-dummy.xml");
		//@formatter:off
		assertThat(content)
			.containsSequence(
				"<testsuite",
				"<properties>",
				"<property name=\"file.separator\" value=\"" + File.separator + "\"/>",
				"<property name=\"path.separator\" value=\"" + File.pathSeparator + "\"/>",
				"</properties>",
				"<testcase",
				"</testsuite>");
		//@formatter:on
	}

	@Test
	void writesHostNameAndTimestamp() throws Exception {
		DummyTestEngine engine = new DummyTestEngine("dummy");
		engine.addTest("test", () -> {
		});

		LocalDateTime now = LocalDateTime.parse("2016-01-28T14:02:59.123");
		ZoneId zone = ZoneId.systemDefault();

		executeTests(engine, Clock.fixed(ZonedDateTime.of(now, zone).toInstant(), zone));

		String content = readValidXmlFile("TEST-dummy.xml");
		//@formatter:off
		assertThat(content)
			.containsSequence(
				"<testsuite",
				"hostname=\"" + InetAddress.getLocalHost().getHostName() + "\"",
				"timestamp=\"2016-01-28T14:02:59\"",
				"<testcase",
				"</testsuite>");
		//@formatter:on
	}

	@Test
	void printsExceptionWhenReportsDirCannotBeCreated() throws Exception {
		Path reportsDir = tempDirectory.resolve("dummy.txt");
		Files.write(reportsDir, singleton("content"));

		StringWriter out = new StringWriter();
		XmlReportsWritingListener listener = new XmlReportsWritingListener(reportsDir.toString(), new PrintWriter(out));

		listener.testPlanExecutionStarted(TestPlan.from(emptySet()));

		assertThat(out.toString()).containsSequence("Could not create reports directory", "FileAlreadyExistsException",
			"at ");
	}

	@Test
	void printsExceptionWhenReportCouldNotBeWritten() throws Exception {
		EngineDescriptor engineDescriptor = new EngineDescriptor("engine", "Engine");

		Path xmlFile = tempDirectory.resolve("TEST-engine.xml");
		Files.createDirectories(xmlFile);

		StringWriter out = new StringWriter();
		XmlReportsWritingListener listener = new XmlReportsWritingListener(tempDirectory.toString(),
			new PrintWriter(out));

		listener.testPlanExecutionStarted(TestPlan.from(singleton(engineDescriptor)));
		listener.executionFinished(TestIdentifier.from(engineDescriptor), successful());

		assertThat(out.toString()).containsSequence("Could not write XML report", "FileNotFoundException", "at ");
	}

	private void executeTests(TestEngine engine) {
		executeTests(engine, Clock.systemDefaultZone());
	}

	private void executeTests(TestEngine engine, Clock clock) {
		PrintWriter out = new PrintWriter(new StringWriter());
		XmlReportsWritingListener reportListener = new XmlReportsWritingListener(tempDirectory.toString(), out, clock);
		Launcher launcher = createLauncher(engine);
		launcher.registerTestExecutionListeners(reportListener);
		launcher.execute(request().select(forUniqueId(engine.getId())).build());
	}

	private String readValidXmlFile(String filename) throws Exception {
		Path xmlFile = tempDirectory.resolve(filename);
		assertTrue(Files.exists(xmlFile), () -> "File does not exist: " + xmlFile);
		String content = new String(Files.readAllBytes(xmlFile), UTF_8);
		return ensureValidAccordingToJenkinsSchema(content);
	}

}
