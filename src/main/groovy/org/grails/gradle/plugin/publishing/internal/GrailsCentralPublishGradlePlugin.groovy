/*
 * Copyright 2015 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
//file:noinspection DuplicatedCode
package org.grails.gradle.plugin.publishing.internal

import grails.util.GrailsNameUtils
import io.github.gradlenexus.publishplugin.NexusPublishPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.plugins.PluginManager
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.TaskContainer
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin

import static com.bmuschko.gradle.nexus.NexusPlugin.getSIGNING_KEY_ID
import static com.bmuschko.gradle.nexus.NexusPlugin.getSIGNING_PASSWORD
import static com.bmuschko.gradle.nexus.NexusPlugin.getSIGNING_KEYRING

/**
 * A plugin to setup publishing to Grails central repo
 *
 * @author Graeme Rocher
 * @since 3.1
 */
class GrailsCentralPublishGradlePlugin implements Plugin<Project> {

    String getErrorMessage(String missingSetting) {
        return """No '$missingSetting' was specified. Please provide a valid publishing configuration. Example:

grailsPublish {
    user = 'user'
    key = 'key'
    userOrg = 'my-company' // optional, otherwise published to personal bintray account
    repo = 'plugins' // optional, defaults to 'plugins'


    websiteUrl = 'http://foo.com/myplugin'
    license {
        name = 'Apache-2.0'
    }
    issueTrackerUrl = 'http://github.com/myname/myplugin/issues'
    vcsUrl = 'http://github.com/myname/myplugin'
    title = "My plugin title"
    desc = "My plugin description"
    developers = [johndoe:"John Doe"]
}

or

grailsPublish {
    user = 'user'
    key = 'key'
    githubSlug = 'foo/bar'
    license {
        name = 'Apache-2.0'
    }
    title = "My plugin title"
    desc = "My plugin description"
    developers = [johndoe:"John Doe"]
}

Your publishing user and key can also be placed in PROJECT_HOME/gradle.properties or USER_HOME/gradle.properties. For example:

bintrayUser=user
bintrayKey=key

Or using environment variables:

BINTRAY_USER=user
BINTRAY_KEY=key
"""
    }

    @Override
    void apply(Project project) {
        final ExtensionContainer extensionContainer = project.extensions
        final TaskContainer taskContainer = project.tasks
        final GrailsPublishExtension gpe = extensionContainer.create("grailsPublish", GrailsPublishExtension)

        final String artifactoryUsername = project.hasProperty("artifactoryPublishUsername") ? project.artifactoryPublishUsername : System.getenv("ARTIFACTORY_USERNAME") ?: ''
        final String artifactoryPassword = project.hasProperty("artifactoryPublishPassword") ? project.artifactoryPublishPassword : System.getenv("ARTIFACTORY_PASSWORD") ?: ''
        final String ossNexusUrl = project.hasProperty("sonatypeNexusUrl") ? project.sonatypeNexusUrl : System.getenv("SONATYPE_NEXUS_URL") ?: ''
        final String ossSnapshotUrl = project.hasProperty("sonatypeSnapshotUrl") ? project.sonatypeSnapshotUrl : System.getenv("SONATYPE_SNAPSHOT_URL") ?: ''
        final String ossUser = project.hasProperty("sonatypeOssUsername") ? project.sonatypeOssUsername : System.getenv("SONATYPE_USERNAME") ?: ''
        final String ossPass = project.hasProperty("sonatypeOssPassword") ? project.sonatypeOssPassword : System.getenv("SONATYPE_PASSWORD") ?: ''
        final String ossStagingProfileId = project.hasProperty("sonatypeOssStagingProfileId") ? project.sonatypeOssStagingProfileId : System.getenv("SONATYPE_STAGING_PROFILE_ID") ?: ''

        final ExtraPropertiesExtension extraPropertiesExtension = extensionContainer.findByType(ExtraPropertiesExtension)

        extraPropertiesExtension.setProperty(SIGNING_KEY_ID, project.hasProperty(SIGNING_KEY_ID) ? project[SIGNING_KEY_ID] : System.getenv("SIGNING_KEY") ?: null)
        extraPropertiesExtension.setProperty(SIGNING_PASSWORD, project.hasProperty(SIGNING_PASSWORD) ? project[SIGNING_PASSWORD] : System.getenv("SIGNING_PASSPHRASE") ?: null)
        extraPropertiesExtension.setProperty(SIGNING_KEYRING, project.hasProperty(SIGNING_KEYRING) ? project[SIGNING_KEYRING] : System.getenv("SIGNING_KEYRING") ?: null)


        project.afterEvaluate {
            boolean isSnapshot = project.version.endsWith("SNAPSHOT")
            boolean isRelease = !isSnapshot
            final PluginManager pluginManager = project.getPluginManager()
            pluginManager.apply(MavenPublishPlugin.class)

            project.publishing {
                if (isSnapshot) {
                    repositories {
                        maven {
                            credentials {
                                username = artifactoryUsername
                                password = artifactoryPassword
                            }
                            url getDefaultGrailsCentralSnapshotRepo()
                        }
                    }
                }

                publications {
                    maven(MavenPublication) {
                        artifactId project.name

                        doAddArtefact(project, delegate)
                        def sourcesJar = taskContainer.findByName("sourcesJar")
                        if (sourcesJar != null) {
                            artifact sourcesJar
                        }
                        def javadocJar = taskContainer.findByName("javadocJar")
                        if (javadocJar != null) {
                            artifact javadocJar
                        }
                        def extraArtefact = getDefaultExtraArtifact(project)
                        if (extraArtefact) {
                            artifact extraArtefact
                        }

                        pom.withXml {
                            Node pomNode = asNode()

                            if (pomNode.dependencyManagement) {
                                pomNode.dependencyManagement[0].replaceNode {}
                            }

                            if (gpe != null) {
                                pomNode.children().last() + {
                                    def title = gpe.title ?: project.name
                                    delegate.name title
                                    delegate.description gpe.desc ?: title

                                    def websiteUrl = gpe.websiteUrl ?: gpe.githubSlug ? "https://github.com/$gpe.githubSlug" : ''
                                    if (!websiteUrl) {
                                        throw new RuntimeException(getErrorMessage('websiteUrl'))
                                    }

                                    delegate.url websiteUrl


                                    def license = gpe.license
                                    if (license != null) {

                                        def concreteLicense = GrailsPublishExtension.License.LICENSES.get(license.name)
                                        if (concreteLicense != null) {

                                            delegate.licenses {
                                                delegate.license {
                                                    delegate.name concreteLicense.name
                                                    delegate.url concreteLicense.url
                                                    delegate.distribution concreteLicense.distribution
                                                }
                                            }
                                        } else if (license.name && license.url) {
                                            delegate.licenses {
                                                delegate.license {
                                                    delegate.name license.name
                                                    delegate.url license.url
                                                    delegate.distribution license.distribution
                                                }
                                            }
                                        }
                                    } else {
                                        throw new RuntimeException(getErrorMessage('license'))
                                    }

                                    if (gpe.githubSlug) {
                                        delegate.scm {
                                            delegate.url "https://github.com/$gpe.githubSlug"
                                            delegate.connection "scm:git@github.com:${gpe.githubSlug}.git"
                                            delegate.developerConnection "scm:git@github.com:${gpe.githubSlug}.git"
                                        }
                                        delegate.issueManagement {
                                            delegate.system "Github Issues"
                                            delegate.url "https://github.com/$gpe.githubSlug/issues"
                                        }
                                    } else {
                                        if (gpe.vcsUrl) {
                                            delegate.scm {
                                                delegate.url gpe.vcsUrl
                                                delegate.connection "scm:$gpe.vcsUrl"
                                                delegate.developerConnection "scm:$gpe.vcsUrl"
                                            }
                                        } else {
                                            throw new RuntimeException(getErrorMessage('vcsUrl'))
                                        }

                                        if (gpe.issueTrackerUrl) {
                                            delegate.issueManagement {
                                                delegate.system "Issue Tracker"
                                                delegate.url gpe.issueTrackerUrl
                                            }
                                        } else {
                                            throw new RuntimeException(getErrorMessage('issueTrackerUrl'))
                                        }

                                    }

                                    if (gpe.developers) {

                                        delegate.developers {
                                            for (entry in gpe.developers.entrySet()) {
                                                delegate.developer {
                                                    delegate.id entry.key
                                                    delegate.name entry.value
                                                }
                                            }
                                        }
                                    } else {
                                        throw new RuntimeException(getErrorMessage('developers'))
                                    }
                                }

                            }

                            // simply remove dependencies without a version
                            // version-less dependencies are handled with dependencyManagement
                            // see https://github.com/spring-gradle-plugins/dependency-management-plugin/issues/8 for more complete solutions
                            pomNode.dependencies.dependency.findAll {
                                it.version.text().isEmpty()
                            }.each {
                                it.replaceNode {}
                            }
                        }
                    }
                }
            }

            if (isRelease) {
                pluginManager.apply(NexusPublishPlugin.class)
                pluginManager.apply(SigningPlugin.class)

                extensionContainer.configure(SigningExtension, {
                    it.required = isRelease
                    it.sign project.publishing.publications.maven
                })

                project.tasks.withType(io.github.gradlenexus.publishplugin.InitializeNexusStagingRepository).configureEach {
                    shouldRunAfter(project.tasks.withType(Sign))
                }

                project.tasks.withType(Sign) {
                    onlyIf { isRelease }
                }
            }

            if (isRelease) {
                project.nexusPublishing {
                    repositories {
                        sonatype {
                            if (ossNexusUrl) {
                                nexusUrl = project.uri(ossNexusUrl)
                            }
                            if (ossSnapshotUrl) {
                                snapshotRepositoryUrl = project.uri(ossSnapshotUrl)
                            }
                            username = ossUser
                            password = ossPass
                            stagingProfileId = ossStagingProfileId
                        }
                    }
                }
            }

            def installTask = taskContainer.findByName("install")
            def publishToSonatypeTask = taskContainer.findByName('publishToSonatype')
            def closeAndReleaseSonatypeStagingRepositoryTask = taskContainer.findByName('closeAndReleaseSonatypeStagingRepository')
            def publishToMavenLocal = taskContainer.findByName("publishToMavenLocal")
            if (publishToSonatypeTask != null && taskContainer.findByName("publish${GrailsNameUtils.getClassName(defaultClassifier)}") == null) {
                taskContainer.register("publish${GrailsNameUtils.getClassName(defaultClassifier)}", { Task task ->
                    task.dependsOn([publishToSonatypeTask, closeAndReleaseSonatypeStagingRepositoryTask])
                    task.setGroup("publishing")
                })
            }
            if (installTask == null) {
                taskContainer.register("install", { Task task ->
                    task.dependsOn(publishToMavenLocal)
                    task.setGroup("publishing")
                })
            }
        }
    }

    protected void doAddArtefact(Project project, MavenPublication publication) {
        publication.from project.components.java
    }

    protected String getDefaultArtifactType() {
        "grails-$defaultClassifier"
    }

    protected String getDefaultGrailsCentralReleaseRepo() {
        "https://repo.grails.org/grails/plugins3-releases-local"
    }

    protected String getDefaultGrailsCentralSnapshotRepo() {
        "https://repo.grails.org/grails/plugins3-snapshots-local"
    }

    protected Map<String, String> getDefaultExtraArtifact(Project project) {
        def directory
        try {
            directory = project.sourceSets.main.groovy.outputDir

        } catch (Exception e) {
            directory = project.sourceSets.main.output.classesDirs
        }
        [source    : "${directory}/META-INF/grails-plugin.xml".toString(),
         classifier: getDefaultClassifier(),
         extension : 'xml']
    }

    protected String getDefaultClassifier() {
        "plugin"
    }

    protected String getDefaultDescription(Project project) {
        "Grails ${project.name} $defaultClassifier"
    }

    protected String getDefaultRepo() {
        "plugins"
    }
}

