/*
 * Copyright (c) 2020, Red Hat Inc. All rights reserved.
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.graalvm.tests.integration.utils;

import com.sun.security.auth.module.UnixSystem;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.graalvm.tests.integration.utils.versions.QuarkusVersion;
import org.jboss.logging.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.graalvm.tests.integration.RuntimesSmokeTest.BASE_DIR;
import static org.graalvm.tests.integration.utils.BuildAndRunCmds.RUN_JAEGER;
import static org.graalvm.tests.integration.utils.GDBSession.GDB_IM_PROMPT;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @author Michal Karm Babacek <karm@redhat.com>
 */
public class Commands {

    private static final Logger LOGGER = Logger.getLogger(Commands.class.getName());

    public static final String CONTAINER_RUNTIME = getProperty("QUARKUS_NATIVE_CONTAINER-RUNTIME", "podman");
    public static final boolean ROOTLESS_CONTAINER_RUNTIME = Boolean.parseBoolean(getProperty("ROOTLESS_CONTAINER-RUNTIME", "false"));
    // Podman: Error: stats is not supported in rootless mode without cgroups v2
    public static final boolean PODMAN_WITH_SUDO = Boolean.parseBoolean(getProperty("PODMAN_WITH_SUDO", "true"));
    // Docker: Error response from daemon: No such container: {{.MemUsage}}. Stats work when called with sudo.
    public static final boolean DOCKER_WITH_SUDO = Boolean.parseBoolean(getProperty("DOCKER_WITH_SUDO", "false"));
    public static final FailOnPerfRegressionEnum FAIL_ON_PERF_REGRESSION = FailOnPerfRegressionEnum.valueOf(getProperty("FAIL_ON_PERF_REGRESSION", "true").toUpperCase());

    public static final boolean IS_THIS_WINDOWS = System.getProperty("os.name").matches(".*[Ww]indows.*");
    public static final boolean IS_THIS_MACOS = System.getProperty("os.name").matches(".*[Mm]ac.*");
    public static String ARCH = System.getProperty("os.arch");
    private static final Pattern NUM_PATTERN = Pattern.compile("[ \t]*[0-9]+[ \t]*");
    private static final Pattern ALPHANUMERIC_FIRST = Pattern.compile("([a-z0-9]+).*");
    private static final Pattern CONTAINER_STATS_MEMORY = Pattern.compile("(?:table)?[ \t]*([0-9\\.]+)([a-zA-Z]+).*");

    public static final String GRAALVM_EXPERIMENTAL_BEGIN = "<GRAALVM_EXPERIMENTAL_BEGIN>";
    public static final String GRAALVM_EXPERIMENTAL_END = "<GRAALVM_EXPERIMENTAL_END>";
    public static final String GRAALVM_BUILD_OUTPUT_JSON_FILE = "<GRAALVM_BUILD_OUTPUT_JSON_FILE>";
    public static final String GRAALVM_BUILD_OUTPUT_JSON_FILE_SWITCH = "-H:BuildOutputJSONFile=";
    public static final QuarkusVersion QUARKUS_VERSION = new QuarkusVersion();
    // While this looks like an env value it isn't. In particular keep the '-' in '[...]BUILDER-IMAGE'
    // as that's used in CI which uses -Dquarkus.native.builder-image=<value> alternative. See
    // getProperty() function for details.
    public static final String BUILDER_IMAGE = getProperty("QUARKUS_NATIVE_BUILDER-IMAGE", "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:22.3-java17");

    // Debug sessions, GDB commands related timeouts
    // How long to wait for a gdb command output to match a certain regexp:
    public static final long CMD_DEFAULT_TIMEOUT_MS = Long.parseLong(getProperty("CMD_DEFAULT_TIMEOUT_MS", "10000"));
    // It can take as long as 2 minutes to set a breakpoint in GraalVM 23.0 even with: https://github.com/graalvm/mandrel/pull/545
    public static final long CMD_LONG_TIMEOUT_MS = Long.parseLong(getProperty("CMD_LONG_TIMEOUT_MS", "80000"));
    // How long to wait for a URL to be reachable during debug session: (when debugging a web app)
    public static final long GOTO_URL_TIMEOUT_MS = Long.parseLong(getProperty("GOTO_URL_TIMEOUT_MS", "250"));
    // Mind that the waiting might be blocked by setting a breakpoint in the meantime. Depend on the test flow.
    public static final long LONG_GOTO_URL_TIMEOUT_MS = Long.parseLong(getProperty("LONG_GOTO_URL_TIMEOUT_MS", "60000"));

    private static final Set<String> getPropertyMessages = new HashSet<>();

    public static String getProperty(String key) {
        return getProperty(key, null);
    }

    public static String getProperty(String key, String defaultValue) {
        String prop = null;
        final String[] alternatives = new String[]{
                key.toUpperCase().replaceAll("[\\.-]+", "_"),
                key.toLowerCase().replaceAll("_+", ".")
        };
        for (String p : alternatives) {
            String env = System.getenv().get(p);
            if (StringUtils.isNotBlank(env)) {
                prop = env;
                break;
            }
            String sys = System.getProperty(p);
            if (StringUtils.isNotBlank(sys)) {
                prop = sys;
                break;
            }
        }
        if (prop == null) {
            final String msg = String.format("Failed to detect any of %s as env or sys props, defaulting to %s",
                    String.join(",", alternatives), defaultValue != null ? defaultValue : "nothing (empty string)");
            if (getPropertyMessages != null && !getPropertyMessages.contains(msg)) {
                getPropertyMessages.add(msg);
                LOGGER.info(msg);
            }
            return defaultValue;
        }
        return prop;
    }

    public static String getUnixUIDGID() {
        if (ROOTLESS_CONTAINER_RUNTIME) {
            return "0:0";
        }
        final UnixSystem s = new UnixSystem();
        return s.getUid() + ":" + s.getGid();
    }

    public static String getBaseDir() {
        final String env = System.getenv().get("basedir");
        final String sys = System.getProperty("basedir");
        if (StringUtils.isNotBlank(env)) {
            return new File(env).getParent();
        }
        if (StringUtils.isBlank(sys)) {
            throw new IllegalArgumentException("Unable to determine project.basedir.");
        }
        return new File(sys).getParent();
    }

    public static void cleanTarget(Apps app) {
        // Apps build
        final String target = BASE_DIR + File.separator + app.dir + File.separator + "target";
        // Apps logging
        final String logs = BASE_DIR + File.separator + app.dir + File.separator + "logs";
        // Dir generated by debug symbols build
        final String sources = BASE_DIR + File.separator + app.dir + File.separator + "sources";
        // Diagnostic data
        final String reports = BASE_DIR + File.separator + app.dir + File.separator + "reports";
        cleanDirOrFile(target, logs, sources, reports);
    }

    public static void cleanDirOrFile(String... path) {
        for (String s : path) {
            try {
                final File f = new File(s);
                FileUtils.forceDelete(f);
                FileUtils.forceDeleteOnExit(f);
            } catch (IOException e) {
                //Silence is golden
            }
        }
    }

    /**
     * Adds prefix on Windows, deals with podman on Linux
     *
     * @param baseCommand
     * @return
     */
    public static List<String> getRunCommand(String... baseCommand) {
        final List<String> runCmd = new ArrayList<>();
        if (IS_THIS_WINDOWS) {
            runCmd.add("cmd");
            runCmd.add("/C");
        } else if ("podman".equals(baseCommand[0]) && PODMAN_WITH_SUDO || "docker".equals(baseCommand[0]) && DOCKER_WITH_SUDO) {
            runCmd.add("sudo");
        }
        runCmd.addAll(Arrays.asList(baseCommand));
        return runCmd;
    }

    public static boolean waitForTcpClosed(String host, int port, long loopTimeoutS) throws InterruptedException, UnknownHostException {
        final InetAddress address = InetAddress.getByName(host);
        long now = System.currentTimeMillis();
        final long startTime = now;
        final InetSocketAddress socketAddr = new InetSocketAddress(address, port);
        while (now - startTime < 1000 * loopTimeoutS) {
            try (Socket socket = new Socket()) {
                // If it lets you write something there, it is still ready.
                socket.connect(socketAddr, 1000);
                socket.setSendBufferSize(1);
                socket.getOutputStream().write(1);
                socket.shutdownInput();
                socket.shutdownOutput();
                LOGGER.info("Socket still available: " + host + ":" + port);
            } catch (IOException e) {
                // Exception thrown - socket is likely closed.
                return true;
            }
            Thread.sleep(1000);
            now = System.currentTimeMillis();
        }
        return false;
    }

    public static int parsePort(String url) {
        return Integer.parseInt(url.split(":")[2].split("/")[0]);
    }

    /**
     * There might be this weird glitch where native-image command completes
     * but the FS does not appear to have the resulting binary ready and executable for the
     * next process *immediately*. Hence, this small wait that mitigates this glitch.
     *
     * Note that nothing happens at the end of the timeout and the TS hopes for the best.
     *
     * @param command
     * @param directory
     */
    public static void waitForExecutable(List<String> command, File directory) {
        long now = System.currentTimeMillis();
        final long startTime = now;
        while (now - startTime < 1000) {
            if (new File(directory.getAbsolutePath() + File.separator + command.get(command.size() - 1)).canExecute()) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
            now = System.currentTimeMillis();
        }
    }

    /**
     * Note that if you provide a logFile, the process output goes to the logFile.
     *
     * @param command
     * @param directory
     * @param logFile
     * @param app
     * @param input
     * @param env
     * @return
     * @throws IOException
     */
    public static Process runCommand(List<String> command, File directory, File logFile, Apps app, File input, Map<String, String> env) throws IOException {
        // Skip the wait if the app runs as a container
        if (app != null && app.runtimeContainer == ContainerNames.NONE) {
            waitForExecutable(command, directory);
        }
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        final Map<String, String> envA = processBuilder.environment();
        envA.put("PATH", System.getenv("PATH"));
        if (env != null) {
            envA.putAll(env);
        }
        processBuilder.directory(directory)
                .redirectErrorStream(true);
        if (logFile != null) {
            final String c = "Command: " + String.join(" ", command) + "\n";
            LOGGER.infof("Command: %s", command);
            Files.write(logFile.toPath(), c.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        }
        if (input != null) {
            processBuilder.redirectInput(input);
        }
        Process pA = null;
        try {
            pA = processBuilder.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return pA;
    }

    public static String runCommand(List<String> command, File directory, Map<String, String> env) throws IOException {
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        final Map<String, String> envA = processBuilder.environment();
        envA.put("PATH", System.getenv("PATH"));
        if (env != null) {
            envA.putAll(env);
        }
        processBuilder.redirectErrorStream(true)
                .directory(directory);
        final Process p = processBuilder.start();
        try (InputStream is = p.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8); // note that UTF-8 would mingle glyphs on Windows
        }
    }

    public static String runCommand(List<String> command, File directory) throws IOException {
        return runCommand(command, directory, null);
    }

    public static String runCommand(List<String> command) throws IOException {
        return runCommand(command, new File("."));
    }

    public static Process runCommand(List<String> command, File directory, File logFile, Apps app, File input) throws IOException {
        return runCommand(command, directory, logFile, app, input, null);
    }

    /**
     * Note that if you provide a logFile, the process output goes to the logFile.
     *
     * @param command
     * @param directory
     * @param logFile
     * @param app
     * @return
     * @throws IOException
     */
    public static Process runCommand(List<String> command, File directory, File logFile, Apps app) throws IOException {
        return runCommand(command, directory, logFile, app, null, null);
    }

    public static void pidKiller(long pid, boolean force) {
        LOGGER.infof("Killing PID: %d, forcefully: %b", pid, force);
        try {
            if (IS_THIS_WINDOWS) {
                if (!force) {
                    final Process p = Runtime.getRuntime().exec(new String[]{
                            BASE_DIR + File.separator + "testsuite" + File.separator + "src" + File.separator + "it" + File.separator + "resources" + File.separator +
                                    "CtrlC.exe ", Long.toString(pid)});
                    p.waitFor(1, TimeUnit.MINUTES);
                }
                Runtime.getRuntime().exec(new String[]{"cmd", "/C", "taskkill", "/PID", Long.toString(pid), "/F", "/T"});
            } else {
                Runtime.getRuntime().exec(new String[]{"kill", force ? "-9" : "-15", Long.toString(pid)});
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    public static boolean waitForContainerLogToMatch(String containerName, Pattern pattern, long timeout, long sleep, TimeUnit unit) throws IOException, InterruptedException {
        final long timeoutMillis = unit.toMillis(timeout);
        final long sleepMillis = unit.toMillis(sleep);
        final long startMillis = System.currentTimeMillis();
        final List<String> cmd = getRunCommand(CONTAINER_RUNTIME, "logs", containerName);
        LOGGER.infof("Command: %s", cmd);
        final ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        final Map<String, String> envA = processBuilder.environment();
        envA.put("PATH", System.getenv("PATH"));
        processBuilder.redirectErrorStream(true);
        while (System.currentTimeMillis() - startMillis < timeoutMillis) {
            final Process p = processBuilder.start();
            try (BufferedReader processOutputReader =
                         new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = processOutputReader.readLine()) != null) {
                    if (pattern.matcher(l).matches()) {
                        return true;
                    }
                }
                p.waitFor();
            }
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    public static List<String> getRunningContainersIDs() throws IOException, InterruptedException {
        final List<String> cmd = getRunCommand(CONTAINER_RUNTIME, "ps");
        LOGGER.infof("Command: %s", cmd);
        final ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        final Map<String, String> envA = processBuilder.environment();
        envA.put("PATH", System.getenv("PATH"));
        processBuilder.redirectErrorStream(true);
        final Process p = processBuilder.start();
        final List<String> ids = new ArrayList<>();
        try (BufferedReader processOutputReader =
                     new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l = processOutputReader.readLine();
            // Skip the first line
            if (l == null || !l.startsWith("CONTAINER ID")) {
                throw new RuntimeException("Unexpected " + CONTAINER_RUNTIME + " command output. Check the daemon.");
            }
            while ((l = processOutputReader.readLine()) != null) {
                final Matcher m = ALPHANUMERIC_FIRST.matcher(l);
                if (m.matches()) {
                    ids.add(m.group(1).trim());
                }
            }
            p.waitFor();
        }
        return Collections.unmodifiableList(ids);
    }

    public static void stopAllRunningContainers() throws InterruptedException, IOException {
        final List<String> ids = getRunningContainersIDs();
        if (!ids.isEmpty()) {
            final List<String> cmd = new ArrayList<>(getRunCommand(CONTAINER_RUNTIME, "stop"));
            cmd.addAll(ids);
            LOGGER.infof("Command: %s", cmd);
            final Process process = Runtime.getRuntime().exec(cmd.toArray(String[]::new));
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static void stopRunningContainers(String... containerNames) throws InterruptedException, IOException {
        for (String name : containerNames) {
            stopRunningContainer(name);
        }
    }

    public static void stopRunningContainer(String containerName) throws InterruptedException, IOException {
        // -t 1, just give it a sec and then kill it; we don't care about long graceful shutdowns. Both podman and docker ok.
        final List<String> cmd = new ArrayList<>(getRunCommand(CONTAINER_RUNTIME, "stop", containerName, "-t", "1"));
        LOGGER.infof("Command: %s", cmd);
        final Process process = Runtime.getRuntime().exec(cmd.toArray(String[]::new));
        process.waitFor(5, TimeUnit.SECONDS);
    }

    public static void removeContainers(String... containerNames) throws InterruptedException, IOException {
        for (String name : containerNames) {
            removeContainer(name);
        }
    }

    public static void removeContainer(String containerName) {
        final List<String> cmd = new ArrayList<>(getRunCommand(CONTAINER_RUNTIME, "rm", containerName, "--force"));
        LOGGER.infof("Command: %s", cmd);
        try {
            final Process process = Runtime.getRuntime().exec(cmd.toArray(String[]::new));
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            LOGGER.errorf("Failed to remove container %s: %s", containerName, e.getMessage());
        }
    }

    /*
    No idea if Docker works with 1024 and Podman with 1000 :-)
    $ podman stats --no-stream --format "table {{.MemUsage}}" my-quarkus-mandrel-app-container
    table 18.06MB / 12.11GB
    $ docker stats --no-stream --format "table {{.MemUsage}}" my-quarkus-mandrel-app-container
    MEM USAGE / LIMIT
    13.43MiB / 11.28GiB
     */
    public static long getContainerMemoryKb(String containerName) throws IOException, InterruptedException {
        final List<String> cmd = getRunCommand(
                CONTAINER_RUNTIME, "stats", "--no-stream", "--format", "table {{.MemUsage}}", containerName);
        LOGGER.infof("Command: %s", cmd);
        final ProcessBuilder pa = new ProcessBuilder(cmd);
        final Map<String, String> envA = pa.environment();
        envA.put("PATH", System.getenv("PATH"));
        pa.redirectErrorStream(true);
        final Process p = pa.start();
        try (BufferedReader processOutputReader =
                     new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = processOutputReader.readLine()) != null) {
                if (l.contains("Error")) {
                    LOGGER.error("Container: " + l);
                    if (l.contains("No such container: {{.MemUsage}}")) {
                        LOGGER.error("You don't have the right to call `stats' on " + containerName + " container. " +
                                "You might have to set " + ("podman".equals(CONTAINER_RUNTIME) ? "PODMAN_WITH_SUDO" : "DOCKER_WITH_SUDO") + " to true.");
                    }
                    break;
                }
                final Matcher m = CONTAINER_STATS_MEMORY.matcher(l);
                if (m.matches()) {
                    float value = Float.parseFloat(m.group(1));
                    String unit = m.group(2);
                    // Yes, precision is just fine here.
                    if (unit.startsWith("M")) {
                        return (long) value * 1024;
                    } else if (unit.startsWith("G")) {
                        return (long) value * 1024 * 1024;
                    } else if (unit.startsWith("k") || unit.startsWith("K")) {
                        return (long) value;
                    } else {
                        throw new IllegalArgumentException("We don't know how to work with memory unit " + unit);
                    }
                }
            }
            p.waitFor();
        }
        return -1L;
    }

    public static long getRSSkB(long pid) throws IOException, InterruptedException {
        ProcessBuilder pa;
        if (IS_THIS_WINDOWS) {
            // Note that PeakWorkingSetSize might be better, but we would need to change it on Linux too...
            // https://docs.microsoft.com/en-us/windows/win32/cimwin32prov/win32-process
            pa = new ProcessBuilder("wmic", "process", "where", "processid=" + pid, "get", "WorkingSetSize");
        } else {
            pa = new ProcessBuilder("ps", "-p", Long.toString(pid), "-o", "rss=");
        }
        final Map<String, String> envA = pa.environment();
        envA.put("PATH", System.getenv("PATH"));
        pa.redirectErrorStream(true);
        final Process p = pa.start();
        try (BufferedReader processOutputReader =
                     new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = processOutputReader.readLine()) != null) {
                if (NUM_PATTERN.matcher(l).matches()) {
                    if (IS_THIS_WINDOWS) {
                        // Qualifiers: DisplayName ("Working Set Size"), Units ("bytes")
                        return Long.parseLong(l.trim()) / 1024L;
                    } else {
                        return Long.parseLong(l.trim());
                    }
                }
            }
            p.waitFor();
        }
        return -1L;
    }

    public static long getOpenedFDs(long pid) throws IOException, InterruptedException {
        ProcessBuilder pa;
        long count = 0;
        if (IS_THIS_WINDOWS) {
            pa = new ProcessBuilder("wmic", "process", "where", "processid=" + pid, "get", "HandleCount");
        } else {
            pa = new ProcessBuilder("lsof", "-F0n", "-p", Long.toString(pid));
        }
        final Map<String, String> envA = pa.environment();
        envA.put("PATH", System.getenv("PATH"));
        pa.redirectErrorStream(true);
        final Process p = pa.start();
        try (BufferedReader processOutputReader =
                     new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            if (IS_THIS_WINDOWS) {
                String l;
                // TODO: We just get a magical number with all FDs... Is it O.K.?
                while ((l = processOutputReader.readLine()) != null) {
                    if (NUM_PATTERN.matcher(l).matches()) {
                        return Long.parseLong(l.trim());
                    }
                }
            } else {
                // TODO: For the time being we count apples and oranges; we might want to distinguish .so and .jar ?
                while (processOutputReader.readLine() != null) {
                    count++;
                }
            }
            p.waitFor();
        }
        return count;
    }

    public static void processStopper(Process p, boolean force) throws InterruptedException {
        processStopper(p, force, false);
    }

    public static void processStopper(Process p, boolean force, boolean orderMatters) throws InterruptedException {
        // TODO: Simplify. "Order matters" should not harm use cases, where order doesn't matter...
        if (orderMatters) {
            final Queue<ProcessHandle> l = new ArrayDeque<>();
            p.children().forEach(l::add);
            l.add(p.toHandle());
            for (int j = 0; j < l.size(); j++) {
                final ProcessHandle ph = l.poll();
                ph.destroy();
                pidKiller(ph.pid(), force);
            }
        } else {
            p.children().forEach(child -> {
                if (child.supportsNormalTermination()) {
                    child.destroy();
                }
                pidKiller(child.pid(), force);
            });
            if (p.supportsNormalTermination()) {
                p.destroy();
                p.waitFor(3, TimeUnit.MINUTES);
            }
            pidKiller(p.pid(), force);
        }
    }

    public static void clearCaches() throws IOException {
        if (IS_THIS_WINDOWS || IS_THIS_MACOS) {
            LOGGER.infof("Not implemented for Windows and Mac");
            return;
        }
        final List<String> cmd = getRunCommand("sudo", "bash", "-c", "sync; echo 3 > /proc/sys/vm/drop_caches");
        LOGGER.infof("Command: %s, Output: %s", cmd, runCommand(cmd));
    }

    public static void disableTurbo() throws IOException {
        if (IS_THIS_WINDOWS || IS_THIS_MACOS) {
            LOGGER.infof("Not implemented for Windows and Mac");
            return;
        }
        final File intel = new File("/sys/devices/system/cpu/intel_pstate/no_turbo");
        if (intel.exists()) {
            final List<String> cmd = getRunCommand("sudo", "bash", "-c", "echo 1 > " + intel.getAbsolutePath());
            LOGGER.infof("Command: %s, Output: %s", cmd, runCommand(cmd));
            return;
        }
        final File amd = new File("/sys/devices/system/cpu/cpufreq/boost");
        if (amd.exists()) {
            final List<String> cmd = getRunCommand("sudo", "bash", "-c", "echo 0 > " + amd.getAbsolutePath());
            LOGGER.infof("Command: %s, Output: %s", cmd, runCommand(cmd));
            return;
        }
        LOGGER.infof("Neither Intel nor AMD turbo boost control found. This is either a vm or a different system.");
    }

    public static void enableTurbo() throws IOException {
        if (IS_THIS_WINDOWS || IS_THIS_MACOS) {
            LOGGER.infof("Not implemented for Windows and Mac");
        }
        final File intel = new File("/sys/devices/system/cpu/intel_pstate/no_turbo");
        if (intel.exists()) {
            final List<String> cmd = getRunCommand("sudo", "bash", "-c", "echo 0 > " + intel.getAbsolutePath());
            LOGGER.infof("Command: %s, Output: %s", cmd, runCommand(cmd));
            return;
        }
        final File amd = new File("/sys/devices/system/cpu/cpufreq/boost");
        if (amd.exists()) {
            final List<String> cmd = getRunCommand("sudo", "bash", "-c", "echo 1 > " + amd.getAbsolutePath());
            LOGGER.infof("Command: %s, Output: %s", cmd, runCommand(cmd));
            return;
        }
        LOGGER.infof("Neither Intel nor AMD turbo boost control found. This is either a vm or a different system.");
    }

    public static class ProcessRunner implements Runnable {
        final File directory;
        final File log;
        final List<String> command;
        final long timeoutMinutes;
        final Map<String, String> envProps;

        public ProcessRunner(File directory, File log, List<String> command, long timeoutMinutes) {
            this.directory = directory;
            this.log = log;
            this.command = command;
            this.timeoutMinutes = timeoutMinutes;
            this.envProps = null;
        }

        public ProcessRunner(File directory, File log, List<String> command, long timeoutMinutes, Map<String, String> envProps) {
            this.directory = directory;
            this.log = log;
            this.command = command;
            this.timeoutMinutes = timeoutMinutes;
            this.envProps = envProps;
        }

        @Override
        public void run() {
            final ProcessBuilder pb = new ProcessBuilder(command);
            final Map<String, String> env = pb.environment();
            env.put("PATH", System.getenv("PATH"));
            if (envProps != null) {
                env.putAll(envProps);
            }
            pb.directory(directory);
            pb.redirectErrorStream(true);
            Process p = null;
            try {
                if (!log.exists()) {
                    Files.createFile(log.toPath());
                }
                final String command = "Command: " + String.join(" ", this.command) + "\n";
                LOGGER.infof("Command: %s", this.command);
                Files.write(log.toPath(), command.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                p = pb.start();
                dumpAndLogProcessOutput(log, p, timeoutMinutes);
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                Objects.requireNonNull(p, "command " + command + " not found/invalid")
                        .waitFor(timeoutMinutes, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void dumpAndLogProcessOutput(File logFile, Process pA, long timeoutMinutes) {
        // We use an executor service and set a timeout to avoid getting stuck in case the underlying process
        // gets stuck and doesn't terminate
        final ExecutorService dumpService = Executors.newSingleThreadExecutor();
        dumpService.submit(() -> {
            InputStream output = pA.getInputStream();
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(output))) {
                String line = bufferedReader.readLine();
                while (line != null) {
                    System.out.println(line);
                    Files.writeString(logFile.toPath(), line + "\n", StandardOpenOption.APPEND);
                    line = bufferedReader.readLine();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        shutdownAndAwaitTermination(dumpService, timeoutMinutes, TimeUnit.MINUTES); // Native image build might take a long time....
    }

    /**
     * The purpose of this method is to read a potentially
     * large binary file of a known structure and to find and to parse a string within it.
     *
     * @param binaryFile, native-image made executable
     * @return list of statically linked libs in native image
     * @throws IOException
     */
    public static Set<String> listStaticLibs(File binaryFile) throws IOException {
        // We cca know the structure of the file. We can skip the start.
        final long skipBytes = 1800;
        // The buffer size window might cut the header in half and miss it. Circular buffer...
        final int bufferSize = 16384;
        final int bufferTail = 1024;
        final byte[] header = "StaticLibraries=".getBytes(US_ASCII);
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(binaryFile))
        ) {
            is.skip(skipBytes);
            final byte[] buffer = new byte[bufferSize + bufferTail];
            int start = -1;
            while ((is.read(buffer, 0, bufferSize)) != -1) {
                if ((start = indexOf(buffer, header)) != -1) {
                    if (bufferSize - start < bufferTail) {
                        //Read some more. The header was at the end of the buffer window.
                        is.read(buffer, bufferSize, bufferTail);
                    }
                    break;
                }
            }
            final Set<String> results = new HashSet<>();
            if (start != -1) {
                final byte[] lib = new byte[64];
                int libc = 0;
                boolean reading = false;
                for (int i = start; i < buffer.length; i++) {
                    if (buffer[i] == 0) {
                        results.add(new String(Arrays.copyOfRange(lib, 0, libc), US_ASCII));
                        break;
                    }
                    if (buffer[i] == '=') {
                        reading = true;
                        continue;
                    }
                    if (reading && buffer[i] != '|') {
                        lib[libc] = buffer[i];
                        libc++;
                        continue;
                    }
                    if (buffer[i] == '|') {
                        results.add(new String(Arrays.copyOfRange(lib, 0, libc), US_ASCII));
                        libc = 0;
                    }
                }
            }
            return results;
        }
    }

    /**
     * @param one
     * @param theOther
     * @return -1 if one doesn't contain theOther, start index otherwise
     */
    public static int indexOf(byte[] one, byte[] theOther) {
        if (one.length < theOther.length || theOther.length == 0) {
            return -1;
        }
        for (int i = 0; i < one.length; i++) {
            int j = 0;
            for (; j < theOther.length; j++) {
                if (i + j >= one.length || one[i + j] != theOther[j]) {
                    break;
                }
            }
            if (j == theOther.length) {
                return i;
            }
        }
        return -1;
    }

    public static boolean searchLogLines(Pattern p, File processLog, Charset charset) throws IOException {
        try (Scanner sc = new Scanner(processLog, charset)) {
            while (sc.hasNextLine()) {
                final Matcher m = p.matcher(sc.nextLine());
                if (m.matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static class PerfRecord {
        public String file;
        public double taskClock = -1;
        public long contextSwitches = -1;
        public long cpuMigrations = -1;
        public long pageFaults = -1;
        public long cycles = -1;
        public long instructions = -1;
        public long branches = -1;
        public long branchMisses = -1;
        public double secondsTimeElapsed = -1;
    }

    public static PerfRecord parsePerfRecord(Path path, String statsFor) throws IOException {
        /*
        An alternative would be to read it all in a one scary chunk:
        final Pattern p = Pattern.compile(".*Performance counter stats for '(?<file>" + filename + ")':\\s*$+" +
                        "\\s*(?<taskclock>[0-9\\.,]*)\\s*msec\\s*task-clock.*$" +
                        "\\s*(?<contextswitches>[0-9\\.,]*)\\s*context-switches.*$" +
                        "\\s*(?<cpumigrations>[0-9\\.,]*)\\s*cpu-migrations.*$" +
                        "\\s*(?<pagefaults>[0-9\\.,]*)\\s*page-faults.*$" +
                        "\\s*(?<cycles>[0-9\\.,]*)\\s*cycles.*$" +
                        "\\s*(?<instructions>[0-9\\.,]*)\\s*instructions.*$" +
                        "\\s*(?<branches>[0-9\\.,]*)\\s*branches.*$" +
                        "\\s*(?<branchmisses>[0-9\\.,]*)\\s*branch-misses.*$+" +
                        "\\s*(?<secondstimeelapsed>[0-9\\.,]*)\\s*seconds time elapsed.*$"
                , Pattern.DOTALL | Pattern.MULTILINE);
       */
        final Pattern begin = Pattern.compile(".*Performance counter stats for\\s+'\\Q" + statsFor + "\\E':.*");
        final Pattern taskClock = Pattern.compile("\\s*([0-9\\.,]+)\\s*msec\\s*task-clock.*$");
        final Pattern contextSwitches = Pattern.compile("\\s*([0-9\\.,]+)\\s*context-switches.*$");
        final Pattern cpuMigrations = Pattern.compile("\\s*([0-9\\.,]+)\\s*cpu-migrations.*$");
        final Pattern pageFaults = Pattern.compile("\\s*([0-9\\.,]+)\\s*page-faults.*$");
        final Pattern cycles = Pattern.compile("\\s*([0-9\\.,]+)\\s*cycles.*$");
        final Pattern instructions = Pattern.compile("\\s*([0-9\\.,]+)\\s*instructions.*$");
        final Pattern branches = Pattern.compile("\\s*([0-9\\.,]+)\\s*branches.*$");
        final Pattern branchMisses = Pattern.compile("\\s*([0-9\\.,]+)\\s*branch-misses.*$");
        final Pattern secondsTimeElapsed = Pattern.compile("\\s*([0-9\\.,]+)\\s*seconds time elapsed.*$");
        try (Scanner sc = new Scanner(path, UTF_8)) {
            while (sc.hasNextLine()) {
                if (begin.matcher(sc.nextLine()).matches()) {
                    break;
                }
            }
            final PerfRecord pr = new PerfRecord();
            pr.file = statsFor;
            while (sc.hasNextLine() && pr.secondsTimeElapsed == -1) {
                final String line = sc.nextLine().replaceAll(",", "");
                Matcher m = taskClock.matcher(line);
                if (m.matches()) {
                    pr.taskClock = Double.parseDouble(m.group(1));
                    continue;
                }
                m = contextSwitches.matcher(line);
                if (m.matches()) {
                    pr.contextSwitches = Long.parseLong(m.group(1));
                    continue;
                }
                m = cpuMigrations.matcher(line);
                if (m.matches()) {
                    pr.cpuMigrations = Long.parseLong(m.group(1));
                    continue;
                }
                m = pageFaults.matcher(line);
                if (m.matches()) {
                    pr.pageFaults = Long.parseLong(m.group(1));
                    continue;
                }
                m = cycles.matcher(line);
                if (m.matches()) {
                    pr.cycles = Long.parseLong(m.group(1));
                    continue;
                }
                m = instructions.matcher(line);
                if (m.matches()) {
                    pr.instructions = Long.parseLong(m.group(1));
                    continue;
                }
                m = branches.matcher(line);
                if (m.matches()) {
                    pr.branches = Long.parseLong(m.group(1));
                    continue;
                }
                m = branchMisses.matcher(line);
                if (m.matches()) {
                    pr.branchMisses = Long.parseLong(m.group(1));
                    continue;
                }
                m = secondsTimeElapsed.matcher(line);
                if (m.matches()) {
                    pr.secondsTimeElapsed = Double.parseDouble(m.group(1));
                }
            }
            return pr;
        }
    }

    public static class SerialGCLog {
        public double timeSpentInGCs = 0;
        public int incrementalGCevents = 0;
        public int fullGCevents = 0;
    }

    public static SerialGCLog parseSerialGCLog(Path path, String statsFor, boolean isJVM) throws IOException {
        final Pattern begin = Pattern.compile(".*\\s+\\Q" + statsFor + "\\E$");
        final Pattern incremental = isJVM ? Pattern.compile("\\[[^]]*]\\[info]\\[gc] GC\\([0-9]+\\) Pause Young \\(Allocation[^)]*\\)[^)]*\\)\\s+([0-9\\.]+)ms$") :
                Pattern.compile("^\\[Incremental\\s+GC\\s+\\(CollectOnAllocation\\)[^,]*,\\s+([0-9\\.]+)\\s+secs\\]$");
        final Pattern full = isJVM ? Pattern.compile("\\[[^]]*]\\[info]\\[gc] GC\\([0-9]+\\) Pause Full \\(Allocation[^)]*\\)[^)]*\\)\\s+([0-9\\.]+)ms$") :
                Pattern.compile("^\\[Full\\s+GC\\s+\\(CollectOnAllocation\\)[^,]*,\\s+([0-9\\.]+)\\s+secs\\]$");
        final Pattern end = Pattern.compile(".*quarkus.*stopped.*");
        try (Scanner sc = new Scanner(path, UTF_8)) {
            while (sc.hasNextLine()) {
                final String l = sc.nextLine();
                if (begin.matcher(l).matches()) {
                    break;
                }
            }
            final SerialGCLog l = new SerialGCLog();
            String line;
            while (sc.hasNextLine() && !end.matcher(line = sc.nextLine()).matches()) {
                Matcher m = incremental.matcher(line);
                if (m.matches()) {
                    l.incrementalGCevents = l.incrementalGCevents + 1;
                    l.timeSpentInGCs = l.timeSpentInGCs + (isJVM ? Double.parseDouble(m.group(1)) / 1000.0 : Double.parseDouble(m.group(1)));
                    continue;
                }
                m = full.matcher(line);
                if (m.matches()) {
                    l.fullGCevents = l.fullGCevents + 1;
                    l.timeSpentInGCs = l.timeSpentInGCs + (isJVM ? Double.parseDouble(m.group(1)) / 1000.0 : Double.parseDouble(m.group(1)));
                }
            }
            return l;
        }
    }

    /**
     * Open an ssh tunnel.
     * @return pid - caller is responsible for closing the tunnel
     */
    public static long openSSHTunnel(String identity, String sshPort, String user, String host, String port, boolean local) {
        final List<String> cmd = getRunCommand(
                "ssh", "-o", "StrictHostKeyChecking=no", "-i", identity, "-p", sshPort,
                local ? "-L" : "-R", port + ":" + host + ":" + port, user + "@" + host, "-N");
        LOGGER.infof("Command: %s", cmd);
        final ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        final Map<String, String> envA = processBuilder.environment();
        envA.put("PATH", System.getenv("PATH"));
        processBuilder.redirectErrorStream(true);
        try {
            final Process p = processBuilder.start();
            return p.pid();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return -1;
    }

    /**
     * @return Podman machine ssh port number
     */
    public static int getPodmanMachineSSHPort() throws IOException {
        final List<String> cmd = getRunCommand(
                CONTAINER_RUNTIME, "machine", "inspect", "--format", "{{.SSHConfig.Port}}");
        LOGGER.infof("Command: %s", cmd);
        final ProcessBuilder pa = new ProcessBuilder(cmd);
        pa.environment().put("PATH", System.getenv("PATH"));
        pa.redirectErrorStream(true);
        final Process p = pa.start();
        try (InputStream is = p.getInputStream()) {
            return Integer.parseInt(new String(is.readAllBytes(), US_ASCII).trim());
        }
    }

    public static String mapToJSON(List<Map<String, String>> maps) {
        final Pattern num = Pattern.compile("\\d+");
        final StringBuilder sb = new StringBuilder();
        sb.append("[");
        maps.forEach(map -> {
            sb.append("{");
            map.forEach((k, v) -> {
                sb.append("\"");
                sb.append(k);
                sb.append("\":");
                if (num.matcher(v).matches()) {
                    sb.append(v);
                } else {
                    sb.append("\"");
                    sb.append(v);
                    sb.append("\"");
                }
                sb.append(",");
            });
            sb.setLength(sb.length() - 1);
            sb.append("}");
            sb.append(",");
        });
        sb.setLength(sb.length() - 1);
        sb.append("]");
        return sb.toString();
    }

    public static int waitForFileToMatch(Pattern lineMatchRegexp, Path path, int skipLines, long timeout, long sleep, TimeUnit unit) throws IOException {
        long timeoutMillis = unit.toMillis(timeout);
        long sleepMillis = unit.toMillis(sleep);
        long startMillis = System.currentTimeMillis();
        LOGGER.infof("Waiting for file %s to have a line matching this regexp: %s", path, lineMatchRegexp);
        // Reading the file again and again, could hurt on huge files...
        while (System.currentTimeMillis() - startMillis < timeoutMillis) {
            if (Files.exists(path)) {
                try (final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new ByteArrayInputStream(Files.readAllBytes(path)), StandardCharsets.UTF_8))) {
                    String line;
                    int c = 0;
                    while ((line = reader.readLine()) != null) {
                        c++;
                        if (c > skipLines && lineMatchRegexp.matcher(line).matches()) {
                            return c;
                        }
                    }
                }
            } else {
                LOGGER.error("File " + path + " is missing");
            }
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
        return -1;
    }

    public static boolean waitForBufferToMatch(StringBuilder report, StringBuffer stringBuffer, Pattern pattern, long timeout, long sleep, TimeUnit unit) {
        final long timeoutMillis = unit.toMillis(timeout);
        final long sleepMillis = unit.toMillis(sleep);
        final long startMillis = System.currentTimeMillis();
        boolean promptSeen = false;
        while (System.currentTimeMillis() - startMillis < timeoutMillis) {
            // Wait for command to complete, i.e. for the prompt to appear.
            // To ensure the prompt appears consistently across gdb versions after every command we use the GDB/MI mode, i.e. the "--interpreter=mi" option.
            // See https://sourceware.org/gdb/onlinedocs/gdb/GDB_002fMI-Output-Syntax.html#GDB_002fMI-Output-Syntax
            if (!promptSeen && GDB_IM_PROMPT.matcher(stringBuffer.toString()).matches()) {
                Logs.appendln(report, "Gdb prompt took " + (System.currentTimeMillis() - startMillis) + " ms to appear");
                promptSeen = true;
            }
            if (promptSeen && pattern.matcher(stringBuffer.toString()).matches()) {
                Logs.appendln(report, "Expected gdb output took " + (System.currentTimeMillis() - startMillis) + " ms to appear");
                return true;
            }
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
        Logs.appendln(report, "Command timed out after " + (System.currentTimeMillis() - startMillis) + " ms without seeing the expected output.");
        return false;
    }

    /**
     * @param switchReplacements
     * Example usage for switch statements:
     * //@formatter:off
     *  final Map<String, String> switches;
     *  if (UsedVersion.getVersion(false).compareTo(Version.create(23, 0, 0)) >= 0) {
     *      switches = Map.of(GRAALVM_ALLOW_DEPRECATED_BUILDER_SWITCH_TOKEN, GRAALVM_ALLOW_DEPRECATED_BUILDER_SWITCH + ",");
     *  } else {
     *      switches = Map.of(GRAALVM_ALLOW_DEPRECATED_BUILDER_SWITCH_TOKEN, "");
     *  }
     *  builderRoutine(1, app, null, null, null, appDir, processLog, null, switches);
     *
     *  Where the constants could be e.g.:
     *
     *  public static final String GRAALVM_ALLOW_DEPRECATED_BUILDER_SWITCH_TOKEN = "<GRAALVM_ALLOW_DEPRECATED_BUILDER>";
     *  public static final String GRAALVM_ALLOW_DEPRECATED_BUILDER_SWITCH = "-H:+AllowDeprecatedBuilderClassesOnImageClasspath";
     *
     *  And in the BuildAndRunCmds e.g.:
     *
     *  new String[]{"mvn", "clean", "package", "-Pnative", "-Dquarkus.version=" + QUARKUS_VERSION.getVersionString(),
     *               "-Dquarkus.native.additional-build-args=" +
     *                GRAALVM_ALLOW_DEPRECATED_BUILDER_SWITCH_TOKEN +
     *               "-R:MaxHeapSize=" + MX_HEAP_MB + "m," +
     *               "-H:-ParseOnce," +
     *               "-H:BuildOutputJSONFile=quarkus-json_minus-ParseOnce.json",
     *               "-Dcustom.final.name=quarkus-json_-ParseOnce"},
     * //@formatter:on
     */
    public static void builderRoutine(Apps app, StringBuilder report, String cn, String mn, File appDir,
                                      File processLog, Map<String, String> env, Map<String, String> switchReplacements) throws IOException {
        String[][] buildCommands = app.buildAndRunCmds.buildCommands;
        assertTrue(buildCommands.length > 0);
        if (report != null) {
            Logs.appendln(report, "# " + cn + ", " + mn);
        }
        for (int i = 0; i < buildCommands.length; i++) {
            // We cannot run commands in parallel, we need them to follow one after another
            final ExecutorService buildService = Executors.newFixedThreadPool(1);
            final List<String> cmd;
            // Replace possible placeholders with actual switches
            if (switchReplacements != null && !switchReplacements.isEmpty()) {
                cmd = replaceSwitchesInCmd(getRunCommand(buildCommands[i]), switchReplacements);
            } else {
                cmd = getRunCommand(buildCommands[i]);
            }
            Files.writeString(processLog.toPath(), String.join(" ", cmd) + "\n", StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            buildService.submit(new Commands.ProcessRunner(appDir, processLog, cmd, 20, env)); // might take a long time....
            if (report != null) {
                Logs.appendln(report, (new Date()).toString());
                Logs.appendln(report, appDir.getAbsolutePath());
                Logs.appendlnSection(report, String.join(" ", cmd));
            }
            shutdownAndAwaitTermination(buildService, 20, TimeUnit.MINUTES); // Native image build might take a long time....
        }
        assertTrue(processLog.exists());
    }

    public static void builderRoutine(Apps app, StringBuilder report, String cn, String mn, File appDir, File processLog) throws IOException {
        builderRoutine(app, report, cn, mn, appDir, processLog, null, null);
    }

    public static void builderRoutine(Apps app, StringBuilder report, String cn, String mn, File appDir, File processLog, Map<String, String> env) throws IOException {
        builderRoutine(app, report, cn, mn, appDir, processLog, env, null);
    }

    public static List<String> replaceSwitchesInCmd(final List<String> cmd, final Map<String, String> switchReplacements) {
        final List<String> newCmd = new ArrayList<>(cmd.size());
        cmd.forEach(c -> {
            String segment = c.trim();
            if (switchReplacements.containsKey(segment)) {
                final String replacement = switchReplacements.get(segment);
                if (!replacement.isEmpty()) {
                    newCmd.add(replacement);
                }
            } else {
                // Some switches could be nested in e.g. -Dquarkus.native.additional-build-args=,
                // thus not found by simple cmd lookup above. There could be more keys to replace too,
                // so we need to iterate until all substitutions in a segment are done. Yes. I am beginning to wonder too.
                final List<String> keys = switchReplacements.keySet().stream().filter(segment::contains).collect(Collectors.toList());
                if (!keys.isEmpty()) {
                    for (String key : keys) {
                        segment = segment.replace(key, switchReplacements.get(key));
                    }
                    newCmd.add(segment);
                } else {
                    newCmd.add(c);
                }
            }
        });
        return newCmd;
    }

    // Copied from
    // https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/concurrent/ExecutorService.html
    private static void shutdownAndAwaitTermination(ExecutorService pool, long timeout, TimeUnit unit) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait for existing tasks to terminate
            if (!pool.awaitTermination(timeout, unit)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(1, TimeUnit.MINUTES))
                    fail("Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    public static void replaceInSmallTextFile(Pattern search, String replace, Path file, Charset charset) throws IOException {
        final String data = Files.readString(file, charset);
        Files.writeString(file, search.matcher(data).replaceAll(replace), charset, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static void replaceInSmallTextFile(Pattern search, String replace, Path file) throws IOException {
        replaceInSmallTextFile(search, replace, file, Charset.defaultCharset());
    }

    /**
     * Finds the first matching executable in a given dir,
     * <b>does not dive into the tree</b>, is not recursive...
     *
     * @param dir
     * @param regexp
     * @return null or the found file
     */
    public static File findExecutable(Path dir, Pattern regexp) {
        if (regexp == null) {
            throw new IllegalArgumentException("Regexp must not be null.");
        }
        if (dir == null || Files.notExists(dir)) {
            throw new IllegalArgumentException("Path to " + dir + " must exist.");
        }
        final File[] f = dir.toFile().listFiles(pathname -> {
            if (pathname.isFile() && Files.isExecutable(pathname.toPath())) {
                return regexp.matcher(pathname.getName()).matches();
            }
            return false;
        });
        if (f == null || f.length < 1) {
            fail("Failed to find any executable in dir " + dir + ", matching regexp " + regexp);
        }
        return f[0];
    }

    public static List<Path> findFiles(Path dir, Pattern regexp) throws IOException {
        if (dir == null || Files.notExists(dir) || !Files.isDirectory(dir) || regexp == null) {
            throw new IllegalArgumentException("Path to " + dir + " must exist, it must be a directory  and regexp must nut be null.");
        }
        final List<Path> files = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (regexp.matcher(file.getFileName().toString()).matches()) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    public static void cleanup(Process process, String cn, String mn, StringBuilder report, Apps app, File... log)
            throws InterruptedException, IOException {
        // Make sure processes are down even if there was an exception / failure
        if (process != null) {
            processStopper(process, true);
        }
        // Archive logs no matter what
        for (File f : log) {
            Logs.archiveLog(cn, mn, f);
        }
        if (app.runtimeContainer != ContainerNames.NONE) {
            removeContainers(app.runtimeContainer.name);
        }
        Logs.writeReport(cn, mn, report.toString());
        cleanTarget(app);
    }

    public static void runJaegerContainer() {
        final List<String> cmd = getRunCommand(RUN_JAEGER.runCommands[0]);
        LOGGER.infof("Command: %s", cmd);
        try {
            LOGGER.infof("Output: %s", runCommand(cmd));
        } catch (Exception e) {
            LOGGER.error("Failed to start Jaeger container", e);
        }
    }
}
