package com.google.protobuf.gradle

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Component
import com.android.build.api.variant.Variant
import com.google.protobuf.gradle.internal.DefaultProtoSourceSet
import com.google.protobuf.gradle.tasks.ProtoSourceSet
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider

@CompileStatic
class ProtobufAndroidSupport {

    @TypeChecked(TypeCheckingMode.SKIP)
    static void configure(Project project, ProtobufPlugin plugin, Provider<Task> dummyTask) {
        project.android.sourceSets.configureEach { sourceSet ->
            ProtoSourceSet protoSourceSet = plugin.protobufExtension.sourceSets.create(sourceSet.name)
            plugin.addSourceSetExtension(sourceSet, protoSourceSet)
            Configuration protobufConfig = plugin.createProtobufConfiguration(protoSourceSet)
            plugin.setupExtractProtosTask(protoSourceSet, protobufConfig, dummyTask)
        }

        NamedDomainObjectContainer<ProtoSourceSet> variantSourceSets =
                project.objects.domainObjectContainer(ProtoSourceSet) { String name ->
                    new DefaultProtoSourceSet(name, project.objects)
                }

        AndroidComponentsExtension androidComponents = project.extensions.getByType(AndroidComponentsExtension)

        androidComponents.onVariants(androidComponents.selector().all()) { Variant variant ->
            List<String> flavors = variant.productFlavors.collect { pair -> pair.second }
            addTasksForVariant(project, plugin, variant, variantSourceSets, dummyTask, flavors, variant.buildType)
            variant.nestedComponents.each { component ->
                addTasksForVariant(project, plugin, component, variantSourceSets, dummyTask, flavors, variant.buildType)
            }
        }
    }

    /**
     * Creates Protobuf tasks for a variant in an Android project.
     */
    @TypeChecked(TypeCheckingMode.SKIP) // Don't depend on AGP
    private static void addTasksForVariant(
            Project project,
            ProtobufPlugin plugin,
            Component variant,
            NamedDomainObjectContainer<ProtoSourceSet> variantSourceSets,
            Provider<Task> dummyTask,
            List<String> flavors = [],
            String buildType = null
    ) {
        ProtoSourceSet variantSourceSet = variantSourceSets.create(variant.name)

        String compileConfigName = variant.name + "CompileClasspath"
        Configuration compileConfiguration = project.configurations.getByName(compileConfigName)

        FileCollection classPathConfig = compileConfiguration.incoming.artifactView {
            attributes.attribute(
                    Attribute.of("artifactType", String),
                    ArtifactTypeDefinition.JAR_TYPE
            )
        }.files

        boolean isUnitTest = variant.name.endsWith("UnitTest")
        boolean isAndroidTest = variant.name.endsWith("AndroidTest")
        boolean isTest = isUnitTest || isAndroidTest

        if (isTest) {
            // Allow test variants to access main protos
            if (plugin.protobufExtension.sourceSets.findByName("main")) {
                variantSourceSet.includesFrom(plugin.protobufExtension.sourceSets.getByName("main"))
            }
        }

        plugin.setupExtractIncludeProtosTask(variantSourceSet, classPathConfig, dummyTask)

        Closure addSourceSetIfExists = { String name ->
            if (plugin.protobufExtension.sourceSets.findByName(name)) {
                variantSourceSet.extendsFrom(plugin.protobufExtension.sourceSets.getByName(name))
            }
        }

        if (isTest) {
            if (isUnitTest) {
                addSourceSetIfExists("test")
            }
            if (isAndroidTest) {
                addSourceSetIfExists("androidTest")
            }
        } else {
            addSourceSetIfExists("main")

            if (buildType != null) {
                addSourceSetIfExists(buildType)
            }
            flavors.each { flavor ->
                addSourceSetIfExists(flavor)
            }
        }

        addSourceSetIfExists(variant.name)

        Provider<GenerateProtoTask> generateProtoTask =
                plugin.addGenerateProtoTask(variantSourceSet) { GenerateProtoTask task ->
                    task.setVariant(variant, isTest)
                    task.flavors = flavors
                    task.buildType = buildType
                    task.doneInitializing()
        }

        variant.sources.java.addGeneratedSourceDirectory(generateProtoTask) { task ->
            task.outputBaseDir
        }

        boolean isLibrary = project.extensions.findByType(LibraryExtension) != null

        // Include source proto files in the compiled archive, so that proto files from
        // dependent projects can import them.
        if (isLibrary && !isTest) {
            String syncTaskName = "process${variant.name.capitalize()}ProtoResources"
            Provider<ProtobufPlugin.ProtoSyncTask> syncProtoTask = project.tasks.
                  register(syncTaskName, ProtobufPlugin.ProtoSyncTask) {
                      ProtobufPlugin.ProtoSyncTask task ->
                          task.description = "Copies .proto files into the resources for packaging in the AAR."
                          task.source.from(variantSourceSet.proto)
                          task.destinationDirectory.set(
                                  project.layout.buildDirectory.dir("generated/proto-resources/${variant.name}"
                                  )
                          )
            }
            variant.sources.resources.addGeneratedSourceDirectory(syncProtoTask) { task ->
                task.destinationDirectory
            }
        }
    }
}
