package io.quarkus.runner.bootstrap;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.quarkus.bootstrap.BootstrapDebug;
import io.quarkus.bootstrap.app.AdditionalDependency;
import io.quarkus.bootstrap.app.ArtifactResult;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.AugmentResult;
import io.quarkus.bootstrap.app.ClassChangeInformation;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.app.SbomResult;
import io.quarkus.bootstrap.classloading.ClassLoaderEventListener;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.bootstrap.util.PropertyUtils;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildResult;
import io.quarkus.builder.item.BuildItem;
import io.quarkus.deployment.QuarkusAugmentor;
import io.quarkus.deployment.builditem.ApplicationClassNameBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.GeneratedFileSystemResourceHandledBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.builditem.MainClassBuildItem;
import io.quarkus.deployment.builditem.TransformedClassesBuildItem;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.BuildSystemTargetBuildItem;
import io.quarkus.deployment.pkg.builditem.DeploymentResultBuildItem;
import io.quarkus.deployment.pkg.builditem.JarBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageBuildItem;
import io.quarkus.deployment.sbom.SbomBuildItem;
import io.quarkus.dev.spi.DevModeType;
import io.quarkus.runtime.LaunchMode;

/**
 * The augmentation task that produces the application.
 */
public class AugmentActionImpl implements AugmentAction {

    private static final Logger log = Logger.getLogger(AugmentActionImpl.class);

    private static final Class[] NON_NORMAL_MODE_OUTPUTS = { GeneratedClassBuildItem.class,
            GeneratedResourceBuildItem.class, ApplicationClassNameBuildItem.class,
            MainClassBuildItem.class, GeneratedFileSystemResourceHandledBuildItem.class,
            TransformedClassesBuildItem.class };

    private final QuarkusBootstrap quarkusBootstrap;
    private final CuratedApplication curatedApplication;
    private final LaunchMode launchMode;
    private final DevModeType devModeType;
    private final List<Consumer<BuildChainBuilder>> chainCustomizers;
    private final List<ClassLoaderEventListener> classLoadListeners;

    /**
     * A map that is shared between all re-runs of the same augment instance. This is
     * only really relevant in dev mode, however it is present in all modes for consistency.
     *
     */
    private final Map<Class<?>, Object> reloadContext = new ConcurrentHashMap<>();

    public AugmentActionImpl(CuratedApplication curatedApplication) {
        this(curatedApplication, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Leaving this here for backwards compatibility, even though this is only internal.
     *
     * @Deprecated use one of the other constructors
     */
    @Deprecated
    public AugmentActionImpl(CuratedApplication curatedApplication, List<Consumer<BuildChainBuilder>> chainCustomizers) {
        this(curatedApplication, chainCustomizers, Collections.emptyList());
    }

    public AugmentActionImpl(CuratedApplication curatedApplication, List<Consumer<BuildChainBuilder>> chainCustomizers,
            List<ClassLoaderEventListener> classLoadListeners) {
        this.quarkusBootstrap = curatedApplication.getQuarkusBootstrap();
        this.curatedApplication = curatedApplication;
        this.chainCustomizers = chainCustomizers;
        this.classLoadListeners = classLoadListeners;
        LaunchMode launchMode;
        DevModeType devModeType;
        switch (quarkusBootstrap.getMode()) {
            case DEV:
                launchMode = LaunchMode.DEVELOPMENT;
                devModeType = DevModeType.LOCAL;
                break;
            case PROD:
                launchMode = LaunchMode.NORMAL;
                devModeType = null;
                break;
            case RUN:
                launchMode = LaunchMode.RUN;
                devModeType = null;
                break;
            case TEST:
                launchMode = LaunchMode.TEST;
                devModeType = null;
                break;
            case REMOTE_DEV_CLIENT:
                //this seems a bit counterintuitive, but the remote dev client just keeps a production
                //app up to date and ships it to the remote side, this allows the remote side to be fully
                //up-to-date even if the process is restarted
                launchMode = LaunchMode.NORMAL;
                devModeType = DevModeType.REMOTE_LOCAL_SIDE;
                break;
            case CONTINUOUS_TEST:
                //the process that actually launches the tests is a dev mode process
                launchMode = LaunchMode.DEVELOPMENT;
                devModeType = DevModeType.TEST_ONLY;
                break;
            case REMOTE_DEV_SERVER:
                launchMode = LaunchMode.DEVELOPMENT;
                devModeType = DevModeType.REMOTE_SERVER_SIDE;
                break;
            default:
                throw new RuntimeException("Unknown launch mode " + quarkusBootstrap.getMode());
        }
        this.launchMode = launchMode;
        this.devModeType = devModeType;
    }

    @Override
    public void performCustomBuild(String resultHandler, Object context, String... finalOutputs) {
        try (QuarkusClassLoader classLoader = curatedApplication.createDeploymentClassLoader()) {
            Class<? extends BuildItem>[] targets = Arrays.stream(finalOutputs)
                    .map(new Function<String, Class<? extends BuildItem>>() {
                        @Override
                        public Class<? extends BuildItem> apply(String s) {
                            try {
                                return (Class<? extends BuildItem>) Class.forName(s, false, classLoader);
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }).toArray(Class[]::new);
            BuildResult result = runAugment(true, Collections.emptySet(), null, classLoader, targets);

            writeDebugSourceFile(result);
            try {
                BiConsumer<Object, BuildResult> consumer = (BiConsumer<Object, BuildResult>) Class
                        .forName(resultHandler, false, classLoader)
                        .getConstructor().newInstance();
                consumer.accept(context, result);
            } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | ClassNotFoundException
                    | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public AugmentResult createProductionApplication() {
        if (launchMode != LaunchMode.NORMAL) {
            throw new IllegalStateException("Can only create a production application when using NORMAL launch mode");
        }
        try (QuarkusClassLoader classLoader = curatedApplication.createDeploymentClassLoader()) {
            BuildResult result = runAugment(true, Collections.emptySet(), null, classLoader, ArtifactResultBuildItem.class,
                    DeploymentResultBuildItem.class, SbomBuildItem.class);

            writeDebugSourceFile(result);

            JarBuildItem jarBuildItem = result.consumeOptional(JarBuildItem.class);
            NativeImageBuildItem nativeImageBuildItem = result.consumeOptional(NativeImageBuildItem.class);
            List<ArtifactResultBuildItem> artifactResultBuildItems = result.consumeMulti(ArtifactResultBuildItem.class);
            BuildSystemTargetBuildItem buildSystemTargetBuildItem = result.consume(BuildSystemTargetBuildItem.class);
            Map<Path, List<SbomResult>> sboms = getSboms(result.consumeMulti(SbomBuildItem.class));

            // this depends on the fact that the order in which we can obtain MultiBuildItems is the same as they are produced
            // we want to write result of the final artifact created
            if (artifactResultBuildItems.isEmpty()) {
                throw new IllegalStateException("No artifact results were produced");
            }
            writeArtifactResultMetadataFile(buildSystemTargetBuildItem, artifactResultBuildItems);

            return new AugmentResult(artifactResultBuildItems.stream()
                    .map(a -> new ArtifactResult(a.getPath(), a.getType(), a.getMetadata()))
                    .collect(Collectors.toList()),
                    jarBuildItem != null ? jarBuildItem.toJarResult(sboms.getOrDefault(jarBuildItem.getPath(), List.of()))
                            : null,
                    nativeImageBuildItem != null ? nativeImageBuildItem.getPath() : null,
                    nativeImageBuildItem != null ? nativeImageBuildItem.getGraalVMInfo().toMap() : Map.of());
        }
    }

    private Map<Path, List<SbomResult>> getSboms(List<SbomBuildItem> sbomBuildItems) {
        if (sbomBuildItems.isEmpty()) {
            return Map.of();
        }
        final Map<Path, List<SbomResult>> result = new HashMap<>();
        for (var sbomBuildItem : sbomBuildItems) {
            result.computeIfAbsent(sbomBuildItem.getResult().getApplicationRunner(), p -> new ArrayList<>())
                    .add(sbomBuildItem.getResult());
        }
        return result;
    }

    private void writeDebugSourceFile(BuildResult result) {
        String debugSourcesDir = BootstrapDebug.debugSourcesDir();
        if (debugSourcesDir != null) {
            for (GeneratedClassBuildItem i : result.consumeMulti(GeneratedClassBuildItem.class)) {
                try {
                    if (i.getSource() != null) {
                        File debugPath = new File(debugSourcesDir);
                        if (!debugPath.exists()) {
                            debugPath.mkdir();
                        }
                        File sourceFile = new File(debugPath, i.getName() + ".zig");
                        sourceFile.getParentFile().mkdirs();
                        Files.write(sourceFile.toPath(), i.getSource().getBytes(StandardCharsets.UTF_8),
                                StandardOpenOption.CREATE);
                        log.infof("Wrote source: %s", sourceFile.getAbsolutePath());
                    } else {
                        log.infof("Source not available: %s", i.getName());
                    }
                } catch (Exception t) {
                    log.errorf(t, "Failed to write debug source file: %s", i.getName());
                }
            }
        }
    }

    private void writeArtifactResultMetadataFile(BuildSystemTargetBuildItem outputTargetBuildItem,
            List<ArtifactResultBuildItem> artifactResultBuildItems) {
        ArtifactResultBuildItem lastArtifact = artifactResultBuildItems.get(artifactResultBuildItems.size() - 1);
        Path quarkusArtifactMetadataPath = outputTargetBuildItem.getOutputDirectory().resolve("quarkus-artifact.properties");
        Properties properties = new Properties();
        properties.put("type", lastArtifact.getType());
        if (lastArtifact.getPath() != null) {
            properties.put("path", artifactPathForResultMetadata(outputTargetBuildItem, lastArtifact));
        } else {
            if (lastArtifact.getType().endsWith("-container")) {
                // in this case we write "path" as to contain the path to the artifact from which the container was built
                try {
                    ArtifactResultBuildItem baseArtifact = artifactResultBuildItems.get(artifactResultBuildItems.size() - 2);
                    if (baseArtifact.getPath() != null) {
                        properties.put("path", artifactPathForResultMetadata(outputTargetBuildItem, baseArtifact));
                    }
                } catch (IndexOutOfBoundsException e) {
                    // this should never happen really as a container is always built from some other artifact
                    log.debug(e);
                }
            }
        }
        Map<String, String> metadata = lastArtifact.getMetadata();
        if (metadata != null) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                properties.put("metadata." + entry.getKey(), entry.getValue());
            }
        }
        try {
            PropertyUtils.store(properties, quarkusArtifactMetadataPath, "Generated by Quarkus - Do not edit manually");
        } catch (IOException e) {
            log.debug("Unable to write artifact result metadata file", e);
        }
    }

    private static String artifactPathForResultMetadata(BuildSystemTargetBuildItem outputTargetBuildItem,
            ArtifactResultBuildItem artifactResultBuildItem) {
        return outputTargetBuildItem.getOutputDirectory().relativize(artifactResultBuildItem.getPath()).toString();
    }

    @Override
    public StartupActionImpl createInitialRuntimeApplication() {
        if (launchMode == LaunchMode.NORMAL) {
            throw new IllegalStateException("Cannot launch a runtime application with NORMAL launch mode");
        }
        try (QuarkusClassLoader classLoader = curatedApplication.createDeploymentClassLoader()) {
            @SuppressWarnings("unchecked")
            BuildResult result = runAugment(true, Collections.emptySet(), null, classLoader, NON_NORMAL_MODE_OUTPUTS);
            return new StartupActionImpl(curatedApplication, result);
        }
    }

    @Override
    public StartupActionImpl reloadExistingApplication(boolean hasStartedSuccessfully, Set<String> changedResources,
            ClassChangeInformation classChangeInformation) {
        if (launchMode != LaunchMode.DEVELOPMENT) {
            throw new IllegalStateException("Only application with launch mode DEVELOPMENT can restart");
        }

        try (QuarkusClassLoader classLoader = curatedApplication.createDeploymentClassLoader()) {
            @SuppressWarnings("unchecked")
            BuildResult result = runAugment(!hasStartedSuccessfully, changedResources, classChangeInformation, classLoader,
                    NON_NORMAL_MODE_OUTPUTS);

            return new StartupActionImpl(curatedApplication, result);
        }
    }

    private BuildResult runAugment(boolean firstRun, Set<String> changedResources,
            ClassChangeInformation classChangeInformation, ClassLoader deploymentClassLoader,
            Class<? extends BuildItem>... finalOutputs) {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try {
            QuarkusClassLoader classLoader = curatedApplication.getOrCreateAugmentClassLoader();
            Thread.currentThread().setContextClassLoader(classLoader);
            LaunchMode.set(launchMode);

            QuarkusAugmentor.Builder builder = QuarkusAugmentor.builder()
                    .setRoot(quarkusBootstrap.getApplicationRoot())
                    .setClassLoader(classLoader)
                    .setTargetDir(quarkusBootstrap.getTargetDirectory())
                    .setDeploymentClassLoader(deploymentClassLoader)
                    .setBuildSystemProperties(quarkusBootstrap.getBuildSystemProperties())
                    .setRuntimeProperties(quarkusBootstrap.getRuntimeProperties())
                    .setEffectiveModel(curatedApplication.getApplicationModel())
                    .setDependencyInfoProvider(quarkusBootstrap.getDependencyInfoProvider());
            if (quarkusBootstrap.getBaseName() != null) {
                builder.setBaseName(quarkusBootstrap.getBaseName());
            }
            if (quarkusBootstrap.getOriginalBaseName() != null) {
                builder.setOriginalBaseName(quarkusBootstrap.getOriginalBaseName());
            }

            boolean auxiliaryApplication = curatedApplication.getQuarkusBootstrap().isAuxiliaryApplication();
            builder.setAuxiliaryApplication(auxiliaryApplication);
            builder.setAuxiliaryDevModeType(
                    curatedApplication.getQuarkusBootstrap().isHostApplicationIsTestOnly() ? DevModeType.TEST_ONLY
                            : (auxiliaryApplication ? DevModeType.LOCAL : null));
            builder.setLaunchMode(launchMode);
            builder.setDevModeType(devModeType);
            builder.setTest(curatedApplication.getQuarkusBootstrap().isTest());
            builder.setRebuild(quarkusBootstrap.isRebuild());
            if (firstRun) {
                builder.setLiveReloadState(
                        new LiveReloadBuildItem(false, Collections.emptySet(), reloadContext, classChangeInformation));
            } else {
                builder.setLiveReloadState(
                        new LiveReloadBuildItem(true, changedResources, reloadContext, classChangeInformation));
            }
            for (AdditionalDependency i : quarkusBootstrap.getAdditionalApplicationArchives()) {
                //this gets added to the class path either way
                //but we only need to add it to the additional app archives
                //if it is forced as an app archive
                if (i.isForceApplicationArchive()) {
                    builder.addAdditionalApplicationArchive(i.getResolvedPaths());
                }
            }
            builder.excludeFromIndexing(quarkusBootstrap.getExcludeFromClassPath());
            for (Consumer<BuildChainBuilder> i : chainCustomizers) {
                builder.addBuildChainCustomizer(i);
            }
            for (Class<? extends BuildItem> i : finalOutputs) {
                builder.addFinal(i);
            }

            try {
                return builder.build().run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(old);
            //QuarkusConfigFactory.setConfig(null);
        }
    }

    /**
     * A task that can be used in isolated environments to do a build
     */
    @SuppressWarnings("unused")
    public static class BuildTask implements BiConsumer<CuratedApplication, Map<String, Object>> {

        @Override
        public void accept(CuratedApplication application, Map<String, Object> stringObjectMap) {
            AugmentAction action = new AugmentActionImpl(application);
            action.createProductionApplication();
        }
    }
}
