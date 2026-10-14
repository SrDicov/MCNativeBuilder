package me.judge.mcnativebuilder;

import me.judge.mcnativebuilder.processors.AndroidProcessor;
import me.judge.mcnativebuilder.processors.FabricProcessor;
import me.judge.mcnativebuilder.processors.IProcessor;
import me.judge.mcnativebuilder.processors.LWJGLProcessor;
import com.google.gson.Gson;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.angelauramc.judgelib.JudgeLibAPI;
import org.angelauramc.judgelib.impl.InitInfo;
import org.angelauramc.judgelib.installer.JudgeLibInstall;
import org.angelauramc.judgelib.installer.LoaderType;
import org.angelauramc.judgelib.installer.Source;
import org.angelauramc.judgelib.launcher.BaseJavaLauncher;
import org.angelauramc.judgelib.util.json.MinecraftVersion;
import org.angelauramc.judgelib.util.json.auth.JudgeLibAccount;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Main {
    static {
        if(System.getProperty("os.name").contains("Windows")) {
            OS_EXT_SHELL = ".cmd";
        } else {
            OS_EXT_SHELL = "";
        }
    }
    public static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    public static String OS_EXT_SHELL;

    public static String homePath = System.getProperty("user.dir");
    public static File installDir = new File(homePath, "install");
    public static File buildDir;

    private static String version;
    private static String graalvmInstall;
    private static String username;
    public static boolean shared;
    private static String gc;
    private static String march;
    public static String mainClass;
    private static String buildMode;
    private static boolean fabric;
    private static boolean debug;
    public static boolean android;
    private static boolean experimentalLWJGLPatch;
    private static boolean dryRun;
    public static boolean incremental;
    public static String[] extraBuildArgs;
    private static MinecraftVersion mcVersion;

    private static final List<IProcessor> processors = new ArrayList<>();

    public static void main(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("MCNativeBuilder").build()
                .defaultHelp(true)
                .description("Build Minecraft Native Images.");
        parser.addArgument("--mc-version", "--version")
                .setDefault("1.21.11")
                .help("Version of Minecraft to download and compile");
        parser.addArgument("--graalvm")
                .help("Home of your GraalVM SDK (or GRAALVM_HOME)");
        parser.addArgument("--workdir")
                .help("Working directory for install/, assets/, libraries/ (or MCNATIVE_WORKDIR)");
        parser.addArgument("--username")
                .help("Name for the offline tracing session (or MCNATIVE_USERNAME)");
        parser.addArgument("--processors")
                .nargs("*")
                .help("Fully-qualified name of additional processors to use");
        parser.addArgument("--gc")
                .setDefault("serial")
                .help("Garbage collector to use");
        parser.addArgument("--march")
                .setDefault("compatibility")
                .help("native-image -march target: compatibility | native (native = max perf, binary only runs on this CPU)");
        parser.addArgument("-O")
                .setDefault("s")
                .help("Set the build mode, read the Wiki for an explanation");
        parser.addArgument("--fabric")
                .action(Arguments.storeTrue())
                .setDefault(false)
                .help("Installs Fabric support. Read the wiki for usage");
        parser.addArgument("--debug")
                .action(Arguments.storeTrue())
                .setDefault(false)
                .help("Outputs debug information.");
        parser.addArgument("--shared")
                .action(Arguments.storeTrue())
                .setDefault(false)
                .help("Makes the output a shared library");
        parser.addArgument("--extra-build-args")
                .action(Arguments.append())
                .help("Extra native-image arg; repeat as --extra-build-args=<arg> (the = form is required: values starting with - are flags otherwise)");
        parser.addArgument("--experimental-lwjgl")
                .action(Arguments.storeTrue())
                .setDefault(false)
                .help("Patches LWJGL's JNI class to use C Functions instead of JNI");
        parser.addArgument("--android")
                .action(Arguments.storeTrue())
                .setDefault(false)
                .help("Builds a SO library for Android, see Wiki");
        parser.addArgument("--main-class")
                .setDefault("net.minecraft.client.main.Main")
                .help("Specify native image main class");
        parser.addArgument("--dry-run")
                .action(Arguments.storeTrue())
                .setDefault(false)
                .help("Don't run Minecraft before native-imaging. Helpful if you already have reflection configs.");
        parser.addArgument("--incremental")
                .action(Arguments.storeTrue())
                .setDefault(false)
                .help("Native image the base libs and then using the libraries the application binary.");

        Namespace ns;
        try {
            ns = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(2);
            return;
        }

        List<String> processors = ns.getList("processors");

        homePath = opt(ns.getString("workdir"), "MCNATIVE_WORKDIR", System.getProperty("user.dir"));
        installDir = new File(homePath, "install");
        gc = ns.getString("gc");
        march = ns.getString("march");
        version = ns.getString("mc_version");
        graalvmInstall = opt(ns.getString("graalvm"), "GRAALVM_HOME", null);
        if (graalvmInstall == null) {
            die("--graalvm or GRAALVM_HOME is required", 2);
        }
        username = opt(ns.getString("username"), "MCNATIVE_USERNAME", System.getProperty("user.name"));
        experimentalLWJGLPatch = ns.getBoolean("experimental_lwjgl");
        fabric = ns.getBoolean("fabric");
        android = ns.getBoolean("android");
        List<String> extraArgsRaw = ns.getList("extra_build_args");
        extraBuildArgs = extraArgsRaw == null ? new String[0] : extraArgsRaw.toArray(new String[0]);
        mainClass = ns.getString("main_class");
        shared = ns.getBoolean("shared");
        dryRun = ns.getBoolean("dry_run");
        buildMode = ns.getString("O");
        incremental = ns.getBoolean("incremental");
        if (buildMode.equals("pgoi") || buildMode.equals("pgo")) {
            die("-O pgoi/pgo needs --pgo-instrument, which GraalVM 25 dropped; use -O2 --march native instead", 2);
        }

        LOGGER.info("Initialized MCNativeBuilder...");
        if(Main.fabric) {
            Main.processors.add(new FabricProcessor());
        }
        if(Main.android) {
            Main.processors.add(new AndroidProcessor());
        }

        JudgeLibAPI api = JudgeLibAPI.getInstance();
        api.initialize(new InitInfo("d17a73a2-707c-40f5-8c90-d3eda0956f10", "https://login.microsoftonline.com/consumers/", homePath, code -> System.err.println(code.message())));

        if(processors != null) {
            for (String processorPath : processors) {
                try {
                    Main.processors.add((IProcessor) Class.forName(processorPath).getDeclaredConstructor().newInstance());
                    LOGGER.fine("Added processor " + processorPath);
                } catch (ClassNotFoundException | InvocationTargetException | InstantiationException |
                         IllegalAccessException | NoSuchMethodException e) {
                    die("could not load processor " + processorPath + ": " + e, 2);
                }
            }
        }
        try {
            start();
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            die(e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), 1);
        }
    }

    private static void die(String message, int code) {
        if (message == null || message.isBlank()) {
            message = "failed";
        }
        System.err.println("mcnativebuilder: " + message);
        System.exit(code);
    }

    private static String opt(String flag, String env, String def) {
        if (flag != null) {
            return flag;
        }
        String v = System.getenv(env);
        return v != null && !v.isBlank() ? v : def;
    }

    public static void start() {
        processors.add(new LWJGLProcessor());
        LOGGER.fine("Finished adding processors...");

        try {
            JudgeLibInstall install;
            if (fabric) {
                mcVersion = LoaderType.FABRIC.getMetadata().getMinecraftVersion(Main.version);
                install = withTimeout(() -> JudgeLibAPI.getInstance().install(mcVersion.id, "", mcVersion, Path.of(homePath, "install"),
                    Path.of(homePath, "assets"), Path.of(homePath, "libraries")), "install " + version);
                install.addMod(Path.of(homePath, "install"), "fabric-api", List.of(LoaderType.FABRIC), "fabric", Source.MODRINTH);
            } else {
                mcVersion = LoaderType.VANILLA.getMetadata().getMinecraftVersion(Main.version);
                install = withTimeout(() -> JudgeLibAPI.getInstance().install(mcVersion.id, "", mcVersion, Path.of(homePath, "install"),
                        Path.of(homePath, "assets"), Path.of(homePath, "libraries")), "install " + version);
            }
            buildDir = new File(installDir, install.installName);

            LOGGER.info("Built MC Classpath...");

            List<File> libs = new ArrayList<>(Arrays.stream(install.classpath.split(File.pathSeparator)).map(File::new).toList());
            for (IProcessor processor : processors) {
                List<File> processedLibs = processor.processClasspath(install);
                if (processedLibs != null) {
                    libs.addAll(0, processedLibs);
                }
            }

            List<String> extraArgs = new ArrayList<>();
            LOGGER.info("Injecting MCNativeTools-9.8 into Classpath");
            File mclibFile = new File(installDir, "MCNativeTools-9.8.jar");
            if (!mclibFile.exists()) {
                try (InputStream stream = Main.class.getClassLoader().getResourceAsStream("extraLibs/MCNativeTools-9.8.jar")) {
                    if (stream != null) {
                        Files.copy(stream, mclibFile.toPath());
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            libs.add(mclibFile);

            if(!dryRun) {
                // Offline session: Mojang session auth is not required for singleplayer, verified on 1.21.11.
                JudgeLibAccount account = new JudgeLibAccount(username,
                        UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString(),
                        "", Long.MAX_VALUE);
                LOGGER.info("Launching MC as " + username + " with the tracing agent: play until the main menu, then quit cleanly");
                JudgeLibAPI.getInstance().chooseLauncher("JRE");
                BaseJavaLauncher.INSTANCE.setup(new File(graalvmInstall, "bin/java").getAbsolutePath());
                int gameCode = JudgeLibAPI.getInstance().launchInstall(install, account, new String[0], new String[]{"-agentlib:native-image-agent=config-merge-dir=" + buildDir});
                if (gameCode != 0) {
                    die("tracing run failed with exit code " + gameCode, 1);
                }
                LOGGER.info("Tracing done, building the native image from the collected config");
            }

            // The tool jar is not on the game classpath, so native-image never sees its
            // META-INF configs. Materialize the shipped defaults into the config dir,
            // merging with whatever the tracing agent collected.
            mergeDefaultReflectConfig();

            if(experimentalLWJGLPatch) {
                LOGGER.info("Injecting LWJGLPatch-9.8 into Classpath");
                // ponytail: CME-safe removeIf; ponytail ceiling: single pass, no logging of removed entries
                libs.removeIf(f -> f != null && f.getAbsolutePath().contains("org" + File.separator + "ow2" + File.separator + "asm"));
                File lwjglPatch = new File(installDir, "LWJGLPatch-9.8.jar");
                if (!lwjglPatch.exists()) {
                    try (InputStream stream = Main.class.getClassLoader().getResourceAsStream("extraLibs/LWJGLPatch-9.8.jar")) {
                        if (stream != null) {
                            Files.copy(stream, lwjglPatch.toPath());
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                extraArgs.add("-J-javaagent:" + lwjglPatch.getAbsolutePath());
            }

            if(fabric || incremental) {
                libs.remove(new File(install.mainJar));
            }

            if(fabric) {
                mainClass = "me.judge.fabric.FabricMain";
                List<File> toRemove = new ArrayList<>();
                libs.stream().filter(Objects::nonNull).filter((f) -> f.getName().contains("datafixerupper") ||
                        (f.getName().contains("lwjgl") && !f.getName().equals("lwjgl-glfw-classes.jar"))).forEach((file) -> {
                    extraArgs.add("-J-Dfabric.gameLibraries=" + file.getAbsolutePath());
                    toRemove.add(file);
                });
                libs.removeAll(toRemove);
            }

            List<String> sharedArgs = new ArrayList<>();
            if(shared)
                sharedArgs.add("--shared");
            sharedArgs.addAll(List.of(extraBuildArgs));

            if (incremental) {
                // Base layer: everything except the MC jar and the main class, which belong to the app layer.
                List<String> baseArgs = new ArrayList<>(sharedArgs);
                baseArgs.add("-H:LayerCreate=libmcnative-base.nil,package=joptsimple");
                addAll(baseArgs, processors, install, libs, false);

                LOGGER.info("Building base Native Image...");
                // ponytail: base layer is not the exit-code owner; exit handlers go in the app layer
                boolean sharedLib = shared;
                shared = false;
                int baseCode = runCompileBlocking(libs, "libmcnative-base", baseArgs);
                shared = sharedLib;
                if (baseCode != 0) {
                    die("base native-image failed with exit code " + baseCode, 1);
                }

                List<String> appArgs = new ArrayList<>(sharedArgs);
                appArgs.add("-H:LayerUse=libmcnative-base.nil");
                appArgs.add(mainClass);
                addAll(appArgs, processors, install, libs, true);

                LOGGER.info("Building app Native Image...");
                int appCode = runCompileBlocking(List.of(new File(install.mainJar)), version, appArgs);
                if (appCode != 0) {
                    die("app native-image failed with exit code " + appCode, 1);
                }
                LOGGER.info("Built to path " + buildDir.getAbsolutePath());
                return;
            }

            for (IProcessor processor : processors) {
                extraArgs.addAll(processor.preBuild(install, libs, true));
            }
            extraArgs.add(mainClass);
            extraArgs.addAll(sharedArgs);

            LOGGER.info("Building Native Image...");
            int buildCode = runCompileBlocking(libs, version, extraArgs);
            if (buildCode != 0) {
                die("native-image failed with exit code " + buildCode, 1);
            }
            LOGGER.info("Built to path " + buildDir.getAbsolutePath());
        } catch (ReflectiveOperationException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Downloads block with no timeout, so a dead connection hangs forever with no output.
     * ponytail: fixed 30 min ceiling on downloads only; make it a flag if slow links bite.
     */
    private static <T> T withTimeout(Callable<T> task, String what) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }, "mcnativebuilder-" + what);
        worker.setDaemon(true);
        worker.start();
        try {
            return future.get(30, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new IllegalStateException(what + " timed out after 30 minutes (stalled download?)");
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void mergeDefaultReflectConfig() {
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream("META-INF/native-image/me.judge/mcnativebuilder/reflect-config.json")) {
            if (in == null) {
                return;
            }
            Gson gson = new Gson();
            List<Map<String, Object>> merged = new ArrayList<>(
                    gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), List.class));
            File out = new File(buildDir, "reflect-config.json");
            if (out.exists()) {
                List<Map<String, Object>> traced =
                        gson.fromJson(new FileReader(out, StandardCharsets.UTF_8), List.class);
                Set<String> names = new HashSet<>();
                for (Map<String, Object> e : merged) {
                    names.add((String) e.get("name"));
                }
                for (Map<String, Object> e : traced) {
                    if (names.add((String) e.get("name"))) {
                        merged.add(e);
                    }
                }
            }
            try (Writer w = new FileWriter(out, StandardCharsets.UTF_8)) {
                gson.toJson(merged, w);
            }
            LOGGER.info("Reflection config: " + merged.size() + " entries");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void addAll(List<String> args, List<IProcessor> processors, JudgeLibInstall install, List<File> libs, boolean appLayer) {
        for (IProcessor processor : processors) {
            List<String> temp = processor.preBuild(install, libs, appLayer);
            if (temp != null) {
                args.addAll(temp);
            }
        }
    }

    public static int runCompileBlocking(List<File> libs, String out, List<String> extraArgs) {
        try {
            return startCompile(libs, out, extraArgs.toArray(new String[0])).waitFor();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static String quoted(String arg) {
        if (arg.chars().anyMatch(Character::isWhitespace)) {
            return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return arg;
    }

    public static Process startCompile(List<File> classPath, String out, String... extraArgs) throws IOException {
        ProcessBuilder builder = new ProcessBuilder();

        buildDir.mkdirs();
        File argFile = new File(buildDir, "command.txt");
        try (FileWriter writer = new FileWriter(argFile)) {

        for(String arg : List.of("-H:ConfigurationFileDirectories=" + buildDir, "-cp",
                classPath.stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator)), "--gc=" + gc,
                "--enable-url-protocols=https,http",
                "-H:+AddAllCharsets", "-H:+IncludeAllLocales", "-H:IncludeResources=resourcepacks/.*", "-H:IncludeResources=.*.so",
                "-H:IncludeResources=.*.dylib", "-H:IncludeResources=.*.dll", "-H:IncludeResources=.*.jnilib",
                "-H:IncludeResources=data/.*", "-H:IncludeResources=assets/.*",
                "--initialize-at-run-time=io.netty,org.slf4j,sun.net.dns.ResolverConfigurationImpl",
                "-H:+SharedArenaSupport")) {
            writer.write(" ");
            writer.write(quoted(arg));
        }

        if(debug) {
            writer.write(" ");
            writer.write("-g");
        }

        if(shared) {
            writer.write(" ");
            writer.write("-H:+InstallExitHandlers");
        }

        writer.write(" ");
        writer.write("-march=" + march);

        if(buildMode.equals("pgoi")) {
            writer.write(" ");
            writer.write("--pgo-instrument");
        } else if(buildMode.equals("pgo")) {
            writer.write(" ");
            writer.write("--pgo=default.iprof");
        } else {
            writer.write(" ");
            writer.write("-O" + buildMode);
        }

        for(String arg : extraArgs) {
            writer.write(" ");
            writer.write(quoted(arg));
        }
        }

        builder.command(graalvmInstall + "/bin/native-image" + OS_EXT_SHELL, "@" + argFile.getName(), "-o", out);
        builder.directory(buildDir);
        builder.inheritIO();
        return builder.start();
    }
}
